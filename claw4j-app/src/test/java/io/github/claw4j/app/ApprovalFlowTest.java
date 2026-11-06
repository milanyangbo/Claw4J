package io.github.claw4j.app;

import io.github.claw4j.agent.ChatClientAgentPort;
import io.github.claw4j.agent.PermissionFingerprint;
import io.github.claw4j.agent.ToolCallBudget;
import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.approval.ApprovalService;
import io.github.claw4j.agent.approval.CallFingerprint;
import io.github.claw4j.agent.approval.JdbcApprovalLedger;
import io.github.claw4j.agent.approval.ConversationApprovalGate;
import io.github.claw4j.agent.audit.AuditLedger;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.observability.MeteredToolCallback;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.ApproverRoster;
import io.github.claw4j.domain.port.ApprovalDecider;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.ObservationRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 挂起-等批-续跑这一整条链（P02），跑在真库上。
 *
 * <p><b>为什么必须真库</b>：这条链的四个关键判定全在 SQL 里——"同人同工具同参数且未过期
 * 且没被兑现过"是一条 update，"停在这一步"是 run 上的一行状态。
 * 用内存替身的话，这四个条件怎么改都绿，而那正是最容易改错的地方
 * （把 {@code args_digest} 从 where 里删掉，一条用例都不会红）。
 *
 * <p><b>模型是假的那一段</b>：这里验的是"什么时候调模型、调了几次、手里拿到什么"，
 * 不是模型的判断力。真模型的闭环另有一条（{@code ApplicationAssemblyTest}）。
 *
 * <p><b>指纹那两条是变异验证的着力点</b>：{@link #aChangedArgumentDoesNotRideOnAnEarlierGrant}
 * 一旦指纹退化成只算工具名就会红；{@link #theApprovalFingerprintIsNotAnIdempotencyKey}
 * 一旦把 runId 或序号混进指纹就会红。这两个改动都"看起来更严格"，
 * 正是它们需要被钉住的原因。
 */
@Testcontainers
class ApprovalFlowTest {

    private static final String TOOL = "sendEmail";

    private static final String ARGS = "{\"to\":\"zhang@example.com\",\"subject\":\"周报\"}";

    private static final String WIDER_ARGS = "{\"to\":\"all@example.com\",\"subject\":\"周报\"}";

    /** 一条请求等多长。用例里用它判"这条该不该有结论"。 */
    private static final Duration TTL = Duration.ofMinutes(10);

    /** 这些消息从哪个通道来。审批行上的 channel/chat_ref 就是它——批准之后回到这段对话。 */
    private static final String CHANNEL = "feishu";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void freshTables() {
        jdbc.update("delete from claw4j_run.audit_event");
        jdbc.update("delete from claw4j_run.approval_request");
            }

    /**
     * 验收 1：没有批准时 run 挂起、请求直接返回、审批编号回到用户手里，工具一次都没执行。
     *
     * <p>判据全在库里：{@code agent_run.status = suspended}、一条 {@code approval} 类型的
     * step 状态是 awaiting。只断言回话里"提到要审批"是不够的——那样的话，
     * "挂起"变成"回一句话然后照常跑完"也会绿。
     */
    @Test
    @DisplayName("没有批准：这一轮以 done 收尾、审批行带上来处、编号回到用户手里、工具一次没执行")
    void anApprovalRequiredToolEndsTheTurnAndHandsBackAnId() {
        Harness harness = new Harness(requestTool(ARGS), answer("已发送"));
        RecordingTool tool = harness.withApprovalRequiredTool();

        OutboundMessage reply = harness.port().reply(harness.mailer("发一封给张三的周报"));

        assertThat(tool.calls()).as("没有批准，工具一次都不该执行").isZero();
        assertThat(reply.text())
                .startsWith(AuthorizationAdvisor.APPROVAL_PREFIX)
                .as("用户要拿着编号去问「批了吗」；没有编号他只能再发一遍同一句话")
                .contains("审批编号");

        assertThat(finishStatusOf(reply.runId()))
                .as("这一轮确实结束了：它回给用户的是「我需要你批准」。等审批不是一种能跨请求的状态")
                .isEqualTo("succeeded");
        assertThat(pendingApprovals()).hasSize(1);

        // 来处与请求一起落（V21）：批准之后要回到那段对话开新一轮
        assertThat(jdbc.queryForMap("select session_key, channel, chat_ref, principal_snapshot"
                + " from claw4j_run.approval_request"))
                .satisfies(row -> {
                    assertThat(row.get("session_key")).as("哪一段对话（含段）").isNotNull();
                    assertThat(row.get("channel")).isEqualTo(CHANNEL);
                    assertThat(row.get("chat_ref")).isEqualTo("chat-ap");
                    assertThat((String) row.get("principal_snapshot"))
                            .as("发起人快照：批准之后用它调模型，不能用审批人的身份")
                            .contains("u-mailer")
                            .contains("mailer");
                });
    }

    /**
     * 验收 2：批准之后服务端续跑同一 runId，模型拿到的是"这件事做到哪儿了"而不是一句空白。
     *
     * <p>第二次模型调用的<b>入参</b>要看：里面必须有目标，以及"卡在等审批"那一步的痕迹。
     * 只断言"工具最后执行了"的话，"续跑时模型从头猜"这件事永远不会红。
     */
    @Test
    @DisplayName("批准之后在同一段对话里开新一轮：工具终于执行，旧的那一轮仍是 done")
    void approvingStartsTheNextTurnInTheSameConversation() {
        Harness harness = new Harness(requestTool(ARGS), requestTool(ARGS), answer("已发给张三"));
        RecordingTool tool = harness.withApprovalRequiredTool();

        OutboundMessage first = harness.port().reply(harness.mailer("发一封给张三的周报"));
        ApprovalDecider.Result result = harness.decider().decide(onlyPendingId(), "approver-1", true, null);

        assertThat(result.verdict()).isEqualTo(ApprovalVerdict.GRANTED);
        assertThat(tool.calls()).as("批准之后那一次调用真的执行了").isEqualTo(1);
        assertThat(finishStatusOf(first.runId()))
                .as("旧的那一轮不会因为一条迟到的批准被拉起来重跑")
                .isEqualTo("succeeded");

        // 多出来的是同一段对话里的下一轮，不是同一行的续写
        assertThat(jdbc.queryForObject("select count(*) from claw4j_run.audit_event"
                + " where action = 'agent.run.started'", Integer.class))
                .as("批准之后是新一轮（新的一行），不是把旧那行拉起来")
                .isEqualTo(2);
        assertThat(jdbc.queryForList(
                "select distinct session_key from claw4j_run.audit_event"
                        + " where action = 'agent.run.started'", String.class))
                .as("两轮在同一段会话里：上下文与 goal 都在，不用我们从 step 拼一段 recap")
                .hasSize(1);

        assertThat(harness.prompts().getLast())
                .as("交给模型的是审批结论，不是从头再解释一遍背景")
                .contains("[审批通过]")
                .contains(TOOL);
    }

    /**
     * 验收 4：拒绝不执行，但<b>那段对话要被告知</b>。
     *
     * <p>OpenClaw 把两件事分得很清楚：命令是终态（不跑），会话要收到一条 internal followup
     * （"An async command did not run. Do not run the command again."），
     * 否则 agent 会一直等着一个不会来的结果。这里钉的就是后者——只断言"没执行"的话，
     * "模型永远以为还在等"这件事永远不会红。
     */
    @Test
    @DisplayName("拒绝：结论落库、工具没执行，但同一段对话收到「没有执行、不要重试」")
    void denyingTellsTheSameConversationTheCallDidNotRun() {
        Harness harness = new Harness(requestTool(ARGS), answer("好的，我改用别的办法"));
        RecordingTool tool = harness.withApprovalRequiredTool();

        OutboundMessage first = harness.port().reply(harness.mailer("发一封给张三的周报"));
        String approvalId = onlyPendingId();
        String reason = "收件人是外部邮箱，这批不走邮件";

        ApprovalDecider.Result result = harness.decider().decide(approvalId, "approver-1", false, reason);

        assertThat(result.verdict()).isEqualTo(ApprovalVerdict.DENIED);
        assertThat(result.detail()).as("给点按钮的人一句明确的话，带工具名与理由")
                .contains(TOOL).contains(reason);
        assertThat(tool.calls()).isZero();
        assertThat(jdbc.queryForObject("select status from claw4j_run.approval_request where id = ?",
                String.class, approvalId)).isEqualTo("denied");
        assertThat(jdbc.queryForObject("select reason from claw4j_run.approval_request where id = ?",
                String.class, approvalId)).isEqualTo(reason);

        assertThat(harness.prompts()).as("拒绝也要回灌一句：模型不能一直以为还在等").hasSize(2);
        assertThat(harness.prompts().getLast())
                .as("照 OpenClaw 的三句：没有执行、不要重试、没有新输出")
                .contains("[审批被拒]")
                .contains(TOOL)
                .contains(reason)
                .contains("没有执行")
                .contains("不要再重试");
        assertThat(jdbc.queryForObject("select count(*) from claw4j_run.audit_event"
                + " where action = 'agent.run.started'", Integer.class))
                .as("拒绝不是终态收尾，而是同一段对话里的新一轮（模型要去向用户解释）")
                .isEqualTo(2);
        assertThat(finishStatusOf(first.runId()))
                .as("被拦的那一轮早就以 done 收尾了，拒绝不改它")
                .isEqualTo("succeeded");
    }

    /**
     * 验收 3 的后半：过期之后点不动，而且说清是"过期"不是"没有这条"。
     *
     * <p>三种落不下来要分开：编号抄错（没有这条）、已经定过（别点了）、过期了（重新发起）。
     * 合并成一句"操作失败"，点按钮的人只能再点一次。
     */
    @Test
    @DisplayName("过期之后批不动：结论是 EXPIRED，run 仍然挂着等一次新的请求")
    void anExpiredRequestCannotBeApproved() {
        Harness harness = new Harness(requestTool(ARGS), answer("不该走到这里"));
        RecordingTool tool = harness.withApprovalRequiredTool();

        OutboundMessage first = harness.port().reply(harness.mailer("发一封给张三的周报"));
        String approvalId = onlyPendingId();
        jdbc.update("update claw4j_run.approval_request set expires_at = now() - interval '1 second'"
                + " where id = ?", approvalId);

        ApprovalDecider.Result result = harness.decider().decide(approvalId, "approver-1", true, null);

        assertThat(result.verdict()).isEqualTo(ApprovalVerdict.EXPIRED);
        assertThat(tool.calls()).isZero();
        assertThat(finishStatusOf(first.runId()))
                .as("过期的批准不能补点；而这一轮本来就是 done（它回给用户的是「我需要你批准」）")
                .isEqualTo("succeeded");
        assertThat(jdbc.queryForObject("select status from claw4j_run.approval_request where id = ?",
                String.class, approvalId)).isEqualTo("pending");
    }

    /**
     * 验收 3（P03）：<b>提问人不能批自己的请求</b>——自行批准等于没有审批。
     *
     * <p>要点有两个，缺一个就变成"把审批关掉了"：自批时那一行仍然是 {@code pending}
     * （不落结论，别人还能批），以及换一个人点它照样能通过。只断言"自批被拒"的话，
     * 把名单配成空表也能绿——而那时谁都不能批，功能等于不存在。
     */
    @Test
    @DisplayName("提问人批不了自己的请求：不落结论，换个人点照样能通过")
    void theRequesterCannotApproveTheirOwnRequest() {
        Harness harness = new Harness(requestTool(ARGS), requestTool(ARGS), answer("已发给张三"));
        RecordingTool tool = harness.withApprovalRequiredTool();
        Set<String> roster = Set.of("u-mailer", "approver-1");

        OutboundMessage first = harness.port().reply(harness.mailer("发一封给张三的周报"));
        String approvalId = onlyPendingId();

        ApprovalDecider.Result self = harness.decider(roster).decide(approvalId, "u-mailer", true, null);

        assertThat(self.verdict())
                .as("他就在名单里——所以这一档不能并进「不是审批人」")
                .isEqualTo(ApprovalVerdict.SELF_APPROVAL);
        assertThat(self.detail()).contains("不能自己批");
        assertThat(tool.calls()).isZero();
        assertThat(jdbc.queryForObject("select status from claw4j_run.approval_request where id = ?",
                String.class, approvalId))
                .as("自批不落结论：那条请求还挂着等人批，不是被点废")
                .isEqualTo("pending");
        assertThat(finishStatusOf(first.runId())).isEqualTo("succeeded");

        ApprovalDecider.Result other = harness.decider(roster).decide(approvalId, "approver-1", true, null);

        assertThat(other.verdict()).isEqualTo(ApprovalVerdict.GRANTED);
        assertThat(tool.calls()).as("同一条请求换个人点就能过，工具这才执行").isEqualTo(1);
    }

    /**
     * 验收 4（P03）：<b>名单外的人批不了任何一条</b>，而且那一行也不会被点废。
     *
     * <p>名单与业务角色是两张表：这个人在 {@code claw4j.channel.http.roles} 里可能是 admin，
     * 但只要不在 {@code claw4j.approval.approvers} 里，他就批不了——这正是
     * "任何一次提权都静默扩大审批面"要挡住的那条路。
     */
    @Test
    @DisplayName("不在审批人名单里：结论是 NOT_APPROVER，请求仍然挂着等名单里的人")
    void someoneOutsideTheRosterCannotApprove() {
        Harness harness = new Harness(requestTool(ARGS), answer("不该走到这里"));
        RecordingTool tool = harness.withApprovalRequiredTool();

        OutboundMessage first = harness.port().reply(harness.mailer("发一封给张三的周报"));
        String approvalId = onlyPendingId();

        ApprovalDecider.Result outsider = harness.decider().decide(approvalId, "admin-9", true, null);

        assertThat(outsider.verdict()).isEqualTo(ApprovalVerdict.NOT_APPROVER);
        assertThat(outsider.detail()).contains("claw4j.approval.approvers");
        assertThat(tool.calls()).isZero();
        assertThat(jdbc.queryForObject("select decided_by from claw4j_run.approval_request where id = ?",
                String.class, approvalId))
                .as("连 decided_by 都不该被写上：那一行还是没人碰过的样子")
                .isNull();
        assertThat(finishStatusOf(first.runId())).isEqualTo("succeeded");
    }

    /**
     * 变异验证的第一条（票里那条"必须变红"的）：<b>批的是 A，实际要执行的是 B，
     * 换参数不能搭上那次批准。</b>
     *
     * <p>把 {@link CallFingerprint} 退化成"只算工具名"（或者把 {@code args_digest}
     * 从兑现那条 update 的 where 里删掉），这条用例会立刻红：第二次调用直接命中第一次的批准，
     * 工具被执行，而"给全公司的邮件"就在一次点头之下发出去了。
     */
    @Test
    @DisplayName("批准后改参数仍被拦：换参数不能搭上一条批过的批准")
    void aChangedArgumentDoesNotRideOnAnEarlierGrant() {
        Harness harness = new Harness(requestTool(ARGS), requestTool(WIDER_ARGS), answer("不该走到这里"));
        RecordingTool tool = harness.withApprovalRequiredTool();

        OutboundMessage first = harness.port().reply(harness.mailer("发一封给张三的周报"));
        harness.decider().decide(onlyPendingId(), "approver-1", true, null);

        assertThat(tool.calls())
                .as("批的是发给张三，实际要发给全公司——这是另一次，必须重新批")
                .isZero();
        List<String> digests = jdbc.queryForList(
                "select args_digest from claw4j_run.approval_request order by requested_at", String.class);
        assertThat(digests).as("两次调用是两个指纹，不是一条记录被复用").hasSize(2);
        assertThat(digests.get(0)).isNotEqualTo(digests.get(1));
        assertThat(finishStatusOf(first.runId()))
                .as("第二次又卡住了：那一轮的结局仍是 done，新的一轮落在同一段对话里")
                .isEqualTo("succeeded");
    }

    /**
     * 变异验证的第二条（票里那句"幂等键与指纹混用，两个方向的用例都必须变红"）。
     *
     * <p>两个哈希长得很像、口径完全相反，而 ADR-0017 的幂等键<b>还没落代码</b>——
     * 所以这里用它的口径当对照物，而不是等它落地：
     *
     * <ul>
     *   <li>幂等键<b>含</b> runId 与序号：同一件事里同一个调用重放两次要认得出是同一次。</li>
     *   <li>审批指纹<b>不含</b>它们：一次点头要覆盖"这类操作"，否则每件新事都要重批。</li>
     * </ul>
     *
     * <p>第一条断言把"审批指纹不含 runId"钉死——谁把 runId 或序号混进来，它立刻红。
     * 第二条断言把"幂等键必须随 run 变"钉死——把两者混成一个函数的话，
     * 同参数换个 run 会被当成"已经做过"，那是漏执行。
     */
    @Test
    @DisplayName("审批指纹不含 runId 与序号：与幂等键口径相反，混用会双向出事")
    void theApprovalFingerprintIsNotAnIdempotencyKey() {
        String fingerprint = CallFingerprint.of(TOOL, ARGS).orElseThrow();

        assertThat(CallFingerprint.of(TOOL, ARGS)).contains(fingerprint);
        assertThat(CallFingerprint.of(TOOL, WIDER_ARGS).orElseThrow())
                .as("参数变了就是另一个指纹").isNotEqualTo(fingerprint);
        assertThat(CallFingerprint.of("querySales", ARGS).orElseThrow())
                .as("换工具也是另一个指纹").isNotEqualTo(fingerprint);

        String idempotencyKey = idempotencyKey("run-1", 3, TOOL, ARGS);
        assertThat(idempotencyKey)
                .as("幂等键必须随 run 与序号变，否则同一件事里的重放认不出来")
                .isNotEqualTo(idempotencyKey("run-2", 3, TOOL, ARGS))
                .isNotEqualTo(idempotencyKey("run-1", 4, TOOL, ARGS));
        assertThat(fingerprint)
                .as("混用的事故：把幂等键当批准键，每件新事都要重批；"
                        + "反过来同参数第二次执行会被当成已经做过")
                .isNotEqualTo(idempotencyKey("run-1", 3, TOOL, ARGS));
    }

    /**
     * 指纹的规范化：字段顺序不影响结果，值变了才影响。
     *
     * <p>不排序的话，模型换个字段顺序就是另一次调用——审批人会开始闭眼点。
     */
    @Test
    @DisplayName("指纹按 key 递归排序、数字归一：顺序无关，值有关")
    void theFingerprintIgnoresOrderButNotValues() {
        String ordered = CallFingerprint.of(TOOL,
                "{\"to\":\"a@example.com\",\"meta\":{\"b\":2,\"a\":1}}").orElseThrow();

        assertThat(CallFingerprint.of(TOOL,
                "{\"meta\":{\"a\":1,\"b\":2},\"to\":\"a@example.com\"}").orElseThrow())
                .as("同一次调用换个字段顺序，还是同一次").isEqualTo(ordered);
        assertThat(CallFingerprint.of(TOOL,
                "{\"to\":\"a@example.com\",\"meta\":{\"b\":2.0,\"a\":1.00}}").orElseThrow())
                .as("1 与 1.00 在 JSON 里是同一个数").isEqualTo(ordered);
        assertThat(CallFingerprint.of(TOOL,
                "{\"to\":\"b@example.com\",\"meta\":{\"b\":2,\"a\":1}}").orElseThrow())
                .isNotEqualTo(ordered);
    }

    /**
     * 验收 3 的前半：一条批准只放行一次执行，但<b>能跨 run 复用</b>。
     *
     * <p>这两件事看起来相反，其实是同一个口径的两面：指纹不含 runId（所以换一件事还能用），
     * 但兑现过就作废（所以同一份参数不能连放两次）。
     *
     * <p>第一次批准在这里刻意<b>没有被消耗</b>——续跑时模型没再发起工具调用，
     * 于是那条 granted 还躺在库里。这正是"批了但没执行"的真实形状。
     */
    @Test
    @DisplayName("一条批准能跨 run 复用，但用过一次就废")
    void aGrantIsReusedAcrossRunsButOnlyOnce() {
        // 第一件事：被拦 → 批准 → 续跑时模型没再调工具，于是那条批准没被用掉
        Harness first = new Harness(requestTool(ARGS), answer("好的，我等你批"));
        first.withApprovalRequiredTool();
        first.port().reply(first.mailer("发一封给张三的周报"));
        first.decider().decide(onlyPendingId(), "approver-1", true, null);

        // 第二件事：同一个人、同一个工具、同一份参数 —— 直接放行
        Harness second = new Harness(requestTool(ARGS), answer("已发送"));
        RecordingTool secondTool = second.withApprovalRequiredTool();
        OutboundMessage reply = second.port().reply(second.mailer("发一封给张三的周报"));

        assertThat(secondTool.calls())
                .as("指纹不含 runId：上一件事里批过的这一类操作，这一件事不用再批一次")
                .isEqualTo(1);
        assertThat(reply.text()).doesNotContain(AuthorizationAdvisor.APPROVAL_PREFIX);

        // 第三件事：同样参数再来一次 —— 那条批准已经被第二件事用掉了
        Harness third = new Harness(requestTool(ARGS), answer("不该走到这里"));
        RecordingTool thirdTool = third.withApprovalRequiredTool();
        third.port().reply(third.mailer("再发一封给张三的周报"));

        assertThat(thirdTool.calls()).as("一次点头只放行一次执行").isZero();
        assertThat(pendingApprovals()).as("第三次又登记了一条新的待批请求").hasSize(1);
    }

    /**
     * 同一个审批编号被点两次：第二次不翻案，也不重新续跑。
     *
     * <p>两个审批人同时点、或者同一个人手抖点两下，都会走到这里。
     * 允许第二次生效的话，那件事会被同一个批准唤醒两遍。
     */
    @Test
    @DisplayName("同一条审批点两次：第二次是「已经有结论了」，不会再开一轮")
    void decidingTheSameRequestTwiceDoesNotStartAnotherTurn() {
        Harness harness = new Harness(requestTool(ARGS), requestTool(ARGS), answer("已发送"));
        RecordingTool tool = harness.withApprovalRequiredTool();

        harness.port().reply(harness.mailer("发一封给张三的周报"));
        String approvalId = onlyPendingId();
        harness.decider().decide(approvalId, "approver-1", true, null);
        int callsAfterFirst = harness.prompts().size();

        ApprovalDecider.Result again = harness.decider().decide(approvalId, "approver-1", true, null);

        assertThat(again.verdict()).isEqualTo(ApprovalVerdict.ALREADY_DECIDED);
        assertThat(harness.prompts()).as("第二次点击不该再调一次模型").hasSize(callsAfterFirst);
        assertThat(tool.calls()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 审计（P07）

    /**
     * 验收 1（P07）：<b>任一次工具调用都能答出"这次调用是哪次批准的"。</b>
     *
     * <p>判据在视图里，不在回话里：执行那一行带着 {@code approval_id}，视图把它接到
     * 审批表上，于是"谁批的、什么时候、结论是什么"是同一条 select 的三列。
     *
     * <p>同时钉住<b>执行发生在哪一轮</b>：批准之后是同一段对话里的<b>下一轮</b>，
     * 所以那条 {@code succeeded} 事件挂在新的一行 run 上，而不是被拦的那一轮上。
     * 分不清这两者的话，"批准之后接着做"会被读成"同一轮又从被拦的地方爬起来"。
     */
    @Test
    @DisplayName("批准之后执行的那一次：审计行带着审批编号，视图里查得到谁批的")
    void theExecutedCallCarriesTheApprovalItWasLetThroughBy() {
        Harness harness = new Harness(requestTool(ARGS), requestTool(ARGS), answer("已发给张三"));
        RecordingTool tool = harness.withApprovalRequiredTool();

        OutboundMessage first = harness.port().reply(harness.mailer("发一封给张三的周报"));
        String approvalId = onlyPendingId();
        harness.decider().decide(approvalId, "approver-1", true, null);

        assertThat(tool.calls()).isEqualTo(1);
        String executedRunId = jdbc.queryForObject(
                "select run_id from claw4j_run.audit_event"
                        + " where kind = 'tool_action' and status = 'succeeded' limit 1", String.class);
        assertThat(executedRunId)
                .as("执行落在批准之后那一轮：那是新的一行 run，不是被拦的那一条被拉起来了")
                .isNotEqualTo(first.runId());
        Map<String, Object> executed = onlyAuditRow(executedRunId, "succeeded");
        assertThat(executed.get("tool_name")).isEqualTo(TOOL);
        assertThat(executed.get("actor")).isEqualTo("u-mailer");
        assertThat(executed.get("actor_role")).isEqualTo("mailer");
        assertThat(executed.get("approval_id")).isEqualTo(approvalId);
        assertThat(executed.get("call_fingerprint"))
                .as("指纹与审批那条同一个函数，于是「这次执行是哪次批准的」能直接对上")
                .isEqualTo(CallFingerprint.of(TOOL, ARGS).orElseThrow());
        assertThat(executed.toString())
                .as("审计不存参数原文，也不存摘要：那是业务数据的第二份副本")
                .doesNotContain("zhang@example.com");
        assertThat(jdbc.queryForObject(
                "select decided_by from claw4j_run.approval_request where id = ?",
                String.class, approvalId))
                .as("谁批的仍然查得到——在审批表上，不在审计里。审计不复制别处的真相")
                .isEqualTo("approver-1");

        assertThat(jdbc.queryForObject(
                "select count(*) from claw4j_run.audit_event"
                        + " where run_id = ? and kind = 'tool_action'", Integer.class, first.runId()))
                .as("等审批不写审计：那件事的真相在 approval_request 上。"
                        + "被拦的那一轮一条工具事件都没有——它没开始，也不该假装有过一次调用")
                .isZero();
    }

    /**
     * 验收 2（P07）：一次越权落一行，带工具名与角色。
     *
     * <p>越权的判定发生在工具执行之前，{@code MeteredToolCallback} 看不到它——
     * 所以这一行只能由鉴权 Advisor 落。只断言"回话里说了没有权限"是不行的：
     * 那样的话，"拒绝照说了、审计里什么都没有"也会绿。
     */
    @Test
    @DisplayName("一次越权落一行：工具名与角色都在，理由是那句「没有权限」")
    void aRefusedCallLandsAsOneRowWithToolAndRole() {
        Harness harness = new Harness(requestTool(ARGS), answer("好的，我换个说法"));
        // 工具要读 sales_order，而这个人有 read 动作、却一张表都碰不到：
        // 挡住他的确实是表那一格，不是动作那一格（两种拒绝话术不同，用例要挑得清）
        RecordingTool tool = harness.withTool(TOOL, ToolPermission.read("sales_order"));

        OutboundMessage reply = harness.port().reply(harness.reader("查一下上周华东区成交额"));

        assertThat(tool.calls()).as("工具一次都不该执行").isZero();
        Map<String, Object> refused = onlyAuditRow(reply.runId(), "blocked");
        assertThat(refused.get("tool_name")).isEqualTo(TOOL);
        assertThat(refused.get("actor")).isEqualTo("u-analyst");
        assertThat(refused.get("actor_role")).isEqualTo("analyst");
        assertThat(refused.get("approval_id")).as("被拒绝的调用没有批准可指").isNull();
        assertThat(refused.get("error_code"))
                .as("归一化错误码而不是文案：文案改了统计就废")
                .isEqualTo("tool_blocked");
        assertThat(refused.toString())
                .as("理由原文不进库；要查拒在哪条权限上看 trace")
                .doesNotContain("sales_order");
    }

    // ---------------------------------------------------------------- 脚手架

    /**
     * 一条链：真库 + 真审批门 + 假模型。
     *
     * <p>工具与权限是<b>每条用例各自的事</b>，所以 ChatClient 必须在工具登记完之后才建——
     * 先建再登记的话，模型面前压根没有那个工具，用例会以一句谁也看不懂的
     * "脚本用完了"结束。{@link #port()} 因此是懒的。
     */
    private static final class Harness {

        /** 默认名单：这些用例里点按钮的都是这两个人之一。 */
        private static final Set<String> APPROVERS = Set.of("approver-1", "approver-2");

        private final ScriptedModel model;

        private final List<ToolCallback> tools = new ArrayList<>();

        private final Map<String, ToolPermission> declared = new HashMap<>();

        /** 审计落点（P07）：与生产同一条路——工具外面包一层计量/审计的 callback。 */
        private final ToolCallAudit audit = new ToolCallAudit(new AuditLedger(jdbc, null));

        private ChatClientAgentPort port;

        private Harness(ChatResponse... script) {
            this.model = new ScriptedModel(script);
        }

        private RecordingTool withApprovalRequiredTool() {
            return withTool(TOOL, ToolPermission.requiringApproval("send_email"));
        }

        private RecordingTool withTool(String name, ToolPermission permission) {
            RecordingTool tool = new RecordingTool(name);
            declared.put(name, permission);
            tools.add(new MeteredToolCallback(tool, null, audit));
            return tool;
        }

        private ChatClientAgentPort port() {
            if (port == null) {
                ChatClient chat = ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                                ToolCallingAdvisor.builder()
                                        .toolCallingManager(ToolCallBudget.toolCallingManager(
                                                ObservationRegistry.NOOP,
                                                ToolCallBudget.MAX_TOTAL_CALLS)))
                        .defaultAdvisors(new AuthorizationAdvisor(
                                List.of(ToolPermissionRegistry.of(declared)),
                                new ConversationApprovalGate(new JdbcApprovalLedger(jdbc, TTL.toMillis()), List.of()),
                                Metrics.globalRegistry, audit))
                        .defaultToolCallbacks(List.copyOf(tools))
                        .build();
                // 带上审计：这一层的断言读 audit_event 里的两条事件（ADR-0027）。
                port = new ChatClientAgentPort(chat, null, null, null, null, null, audit, null);
            }
            return port;
        }

        /** 每次现造：它只装着几份引用和两条 SQL，没有需要留住的状态。 */
        private ApprovalDecider decider() {
            return decider(APPROVERS);
        }

        /**
         * @param approvers 谁能批（P03）。用例各自决定——"名单外的人批不了"与
         *                  "提问人批不了自己的"要的是不同的名单
         */
        private ApprovalDecider decider(Set<String> approvers) {
            return new ApprovalService(jdbc, new ApproverRoster(approvers), port());
        }

        private List<String> prompts() {
            return model.prompts;
        }

        private InboundMessage mailer(String text) {
            // 有 send_email 动作、没有表权限：第一道门已经过了，挡住的确实是第二道门。
            return new InboundMessage(Principal.of("u-mailer", "mailer", Set.of(), Set.of("send_email")),
                    "chat-ap", text, Instant.now(), null, CHANNEL);
        }

        /** 有 {@code read} 动作、一张表都没有：用来验"表那一格挡住了他"。 */
        private InboundMessage reader(String text) {
            return new InboundMessage(Principal.of("u-analyst", "analyst", Set.of(), Set.of("read")),
                    "chat-ap", text, Instant.now(), null, CHANNEL);
        }
    }

    /** 固定脚本的模型桩：按顺序交出预先写好的响应，把收到的提示词留下来。 */
    private static final class ScriptedModel implements ChatModel {

        private final Deque<ChatResponse> script = new ArrayDeque<>();

        private final List<String> prompts = new ArrayList<>();

        private final ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .model("scripted")
                .temperature(0.0)
                .build();

        private ScriptedModel(ChatResponse... responses) {
            script.addAll(List.of(responses));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(textOf(prompt));
            ChatResponse next = script.poll();
            // 脚本用完还调模型，是"这次改动多调了一次"的信号。返回一句话而不是 null，
            // 让它在那条断言上显式地红，而不是在别处炸出一句看不懂的 NPE。
            return next == null ? answer("（脚本用完了）") : next;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }

        private static String textOf(Prompt prompt) {
            StringBuilder text = new StringBuilder();
            for (Message message : prompt.getInstructions()) {
                text.append(message.getText()).append('\n');
            }
            return text.toString();
        }
    }

    private static List<String> pendingApprovals() {
        return jdbc.queryForList("select id from claw4j_run.approval_request where status = 'pending'",
                String.class);
    }

    /**
     * 审计视图里这一件事的某一种决定，应当正好一行。
     *
     * <p>查的是 {@code claw4j_run.audit_trail} 而不是 {@code agent_step}：
     * 那一层视图就是"审计"在库里的形状（P07），用例跟着读的人走。
     */
    private static Map<String, Object> onlyAuditRow(String runId, String status) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from claw4j_run.audit_event"
                        + " where run_id = ? and action = 'tool.action.finished' and status = ?"
                        + " order by seq", runId, status);
        assertThat(rows).as("这一件事应当正好有一条 %s 的结束事件", status).hasSize(1);
        return rows.getFirst();
    }

    /**
     * 这一轮以什么状态收尾：读审计里那一条 {@code agent.run.finished}。
     *
     * <p>它是"这一轮结束了"唯一的落点（ADR-0027）：以前读 {@code agent_run.status}，
     * 而那张表已经不在了。{@code succeeded} 在这里的含义是"这一轮正常跑完了"——
     * 回给用户一句"我需要你批准"也是一次正常收尾。
     */
    private static String finishStatusOf(String runId) {
        return jdbc.queryForObject(
                "select status from claw4j_run.audit_event where action = 'agent.run.finished'"
                        + " and run_id = ? order by seq desc limit 1", String.class, runId);
    }

    private static String onlyPendingId() {
        List<String> pending = pendingApprovals();
        assertThat(pending).as("这一轮应当正好登记了一条待批请求").hasSize(1);
        return pending.getFirst();
    }

    /**
     * ADR-0017 的幂等键口径，<b>只在这个测试里存在</b>：它还没有实现，
     * 写在这里是为了让"两个哈希口径相反"被一条断言钉住，而不是靠读者记住。
     */
    private static String idempotencyKey(String runId, int stepSeq, String toolName, String args) {
        String canonical = CallFingerprint.canonicalArgs(args).orElseThrow();
        return PermissionFingerprint.sha256(runId + '|' + stepSeq + '|' + toolName + '|' + canonical);
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    private static ChatResponse requestTool(String arguments) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-email", "function", TOOL, arguments)))
                .build();
        return ChatResponse.builder().generations(List.of(new Generation(message))).build();
    }

    /** 记录被真正执行了几次的工具桩。判"没被执行"靠它，不靠回话措辞。 */
    private static final class RecordingTool implements ToolCallback {

        private final ToolDefinition definition;

        private final AtomicInteger calls = new AtomicInteger();

        private RecordingTool(String name) {
            this.definition = ToolDefinition.builder()
                    .name(name)
                    .description("发邮件")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            return "已发送";
        }

        private int calls() {
            return calls.get();
        }
    }
}
