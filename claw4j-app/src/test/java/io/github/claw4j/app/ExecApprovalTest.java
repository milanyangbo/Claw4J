package io.github.claw4j.app;

import io.github.claw4j.agent.approval.AgentExecApprovalPort;
import io.github.claw4j.agent.approval.ApprovalService;
import io.github.claw4j.agent.approval.ConversationApprovalGate;
import io.github.claw4j.agent.approval.JdbcApprovalLedger;
import io.github.claw4j.agent.approval.JdbcExecAllowlist;
import io.github.claw4j.capability.exec.ExecApprovalPort;
import io.github.claw4j.capability.exec.ExecCallContext;
import io.github.claw4j.capability.exec.ExecProperties;
import io.github.claw4j.capability.exec.FrozenPlan;
import io.github.claw4j.domain.ApprovalChoice;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.ApproverRoster;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.ApprovalDecider;
import io.github.claw4j.domain.port.ApprovalLedger;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * exec 的审批链，跑在真库上（票 05）。
 *
 * <p>必须是真库：冻结件比对、一次性兑现、allow-always 落条目全是 SQL 里的判定；换内存替身的话，
 * "把 {@code frozen_plan} 从 redeem 里删掉"、"把 consumed_at 那一句去掉"都不会让任何一条红。
 *
 * <p>不经过模型：这一层验的是"exec 问人的时候发生了什么"，不是模型的判断力。真实的
 * 模型闭环在 {@code ChatPipelineTest}。
 */
@Testcontainers
class ExecApprovalTest {

    private static final Path WORKSPACE = Path.of("/tmp/claw4j-exec");

    private static final Duration TTL = Duration.ofMinutes(10);

    /** 批的人不能是提的人（P03），所以两个名字分开。 */
    private static final String OWNER = "u-runner";

    private static final String APPROVER = "approver-1";

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
        jdbc.update("delete from claw4j_run.approval_request");
        jdbc.update("delete from claw4j_run.exec_allowlist");
    }

    @Test
    @DisplayName("没有身份：不登记也不放行——「批给谁」没有答案时那条请求不该存在")
    void withoutIdentityNothingIsRegistered() {
        ExecApprovalPort.Decision decision = port(ExecProperties.of(ExecProperties.Mode.ASK, WORKSPACE,
                List.of())).decide(request("echo hi"));

        assertThat(decision.kind()).isEqualTo(ExecApprovalPort.Decision.Kind.DENY);
        assertThat(pendingCount()).isZero();
    }

    @Test
    @DisplayName("非 exec 的审批没有冻结件：那一列为空，登记照常")
    void anApprovalWithoutAFrozenPlanIsStillRegistered() {
        ApprovalLedger ledger = new JdbcApprovalLedger(jdbc, TTL.toMillis());

        Optional<String> id = ledger.ask(new ApprovalLedger.Ask("run-1", OWNER, "sendEmail",
                "digest-1", "to=张三", null));

        assertThat(id).isPresent();
        assertThat(jdbc.queryForObject("select frozen_plan is null from claw4j_run.approval_request"
                + " where id = ?", Boolean.class, id.orElseThrow()))
                .as("工具名 + 参数指纹就够定位批的是什么，没有「一条命令」可冻结")
                .isTrue();
    }

    @Test
    @DisplayName("有身份但没批准：登记一条带冻结件的请求，这一轮没有结论")
    void withoutApprovalItRegistersTheFrozenPlan() {
        FrozenPlan plan = FrozenPlan.of("echo hi", WORKSPACE);
        ExecApprovalPort.Decision decision = asOwner(() -> port(ExecProperties.of(ExecProperties.Mode.ASK,
                WORKSPACE, List.of())).decide(request("echo hi")));

        assertThat(decision.kind()).isEqualTo(ExecApprovalPort.Decision.Kind.UNDECIDED);
        assertThat(decision.approvalId()).isNotBlank();
        assertThat(pendingCount()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "select owner, tool_name, args_digest, frozen_plan from claw4j_run.approval_request");
        assertThat(row.get("owner")).isEqualTo(OWNER);
        assertThat(row.get("tool_name")).isEqualTo(AgentExecApprovalPort.TOOL_NAME);
        assertThat(row.get("args_digest")).as("指纹就是冻结件算出来的，改一个字就换一条")
                .isEqualTo(plan.digest());
        assertThat(String.valueOf(row.get("frozen_plan")))
                .as("批的是哪条命令要存下来，执行前拿它逐字符比")
                .contains("echo hi").contains(WORKSPACE.toString());
    }

    @Test
    @DisplayName("批准之后同一条命令：兑现那条批准，放行这一次")
    void approvedCommandRedeemsTheGrant() {
        ExecApprovalPort port = port(ExecProperties.of(ExecProperties.Mode.ASK, WORKSPACE, List.of()));
        String approvalId = asOwner(() -> port.decide(request("echo hi"))).approvalId();
        decideAsApprover(approvalId, ApprovalChoice.ALLOW_ONCE, null);

        ExecApprovalPort.Decision again = asOwner(() -> port.decide(request("echo hi")));

        assertThat(again.kind()).isEqualTo(ExecApprovalPort.Decision.Kind.ALLOW_ONCE);
        assertThat(again.plan().command()).isEqualTo("echo hi");
        assertThat(jdbc.queryForObject("select consumed_at is not null from claw4j_run.approval_request"
                + " where id = ?", Boolean.class, approvalId))
                .as("一次点头只放行一次：兑现之后那条批准不再可用")
                .isTrue();
    }

    @Test
    @DisplayName("批准之后改一个字：旧的冻结件不再命中，登记成新的一条")
    void aChangedCommandDoesNotRideOnTheOldApproval() {
        ExecApprovalPort port = port(ExecProperties.of(ExecProperties.Mode.ASK, WORKSPACE, List.of()));
        String approvalId = asOwner(() -> port.decide(request("echo hi"))).approvalId();
        decideAsApprover(approvalId, ApprovalChoice.ALLOW_ONCE, null);

        ExecApprovalPort.Decision changed = asOwner(() -> port.decide(request("echo hi!")));

        assertThat(changed.kind())
                .as("批的是 `echo hi`，要跑的是 `echo hi!`——旧的批准不能用在新的命令上")
                .isEqualTo(ExecApprovalPort.Decision.Kind.UNDECIDED);
        assertThat(changed.approvalId()).isNotEqualTo(approvalId);
        assertThat(jdbc.queryForObject("select count(*) from claw4j_run.approval_request", Integer.class))
                .as("第一次那条已经是 allowed，第二次是新的一条 pending")
                .isEqualTo(2);
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("选以后都放行：按命令段逐段落条目，第二次不再问")
    void allowAlwaysWritesOneRowPerSegment() {
        JdbcExecAllowlist allowlist = new JdbcExecAllowlist(jdbc);
        String approvalId = asOwner(() -> port(ExecProperties.of(ExecProperties.Mode.ASK, WORKSPACE,
                List.of())).decide(request("rg -n TODO && ls"))).approvalId();

        decideAsApprover(approvalId, ApprovalChoice.ALLOW_ALWAYS, null);

        assertThat(jdbc.queryForList("select pattern from claw4j_run.exec_allowlist order by pattern",
                String.class)).containsExactly("ls", "rg");
        assertThat(allowlist.hasAll(List.of("rg -n TODO", "ls")))
                .as("每一段都落过条目才算持久放行过")
                .isTrue();
        assertThat(allowlist.hasAll(List.of("rg -n TODO", "curl example.com")))
                .as("批了一次 rg 不等于批了 rg && curl")
                .isFalse();
        assertThat(jdbc.queryForObject("select decision from claw4j_run.approval_request where id = ?",
                String.class, approvalId)).isEqualTo("allow-always");
    }

    @Test
    @DisplayName("某一段表达不出可执行名时整条不落：宁可下次再问，也不落一条过宽的")
    void unrepresentableSegmentWritesNothing() {
        JdbcExecAllowlist allowlist = new JdbcExecAllowlist(jdbc);

        // 首 token 是路径：解析不出来就取不到 pattern（取 basename 会让 /tmp/evil/rg 与 rg 共用一条）。
        // 整条不落是刻意的——落一半等于下次只放行其中一段，而人以为自己批的是整条。
        assertThat(allowlist.remember(new FrozenPlan("/tmp/evil/rg -n x && ls", WORKSPACE.toString())))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from claw4j_run.exec_allowlist", Integer.class))
                .as("一条都不该写进去：写进去的那条会在下一次静默放行")
                .isZero();
    }

    @Test
    @DisplayName("ask=always 时选以后都放行：拒收这一档，那条请求仍然挂着")
    void askAlwaysRefusesThePersistentChoice() {
        ExecProperties always = new ExecProperties(ExecProperties.Mode.ASK, null,
                ExecProperties.Ask.ALWAYS, null, null, null, null, WORKSPACE, List.of());
        String approvalId = asOwner(() -> port(always).decide(request("echo hi"))).approvalId();

        ApprovalDecider.Result result = service(always)
                .decide(approvalId, APPROVER, ApprovalChoice.ALLOW_ALWAYS, null);

        assertThat(result.verdict()).isEqualTo(ApprovalVerdict.NOT_APPLICABLE);
        assertThat(jdbc.queryForObject("select status from claw4j_run.approval_request where id = ?",
                String.class, approvalId))
                .as("什么都没改：还可以选一次性的批准")
                .isEqualTo("pending");
        assertThat(jdbc.queryForObject("select count(*) from claw4j_run.exec_allowlist", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("子 Agent 会话被拒：结论落库，但不往那段会话投")
    void denialForASubagentSessionIsNotDelivered() {
        CountingAgent agent = new CountingAgent();
        String approvalId = asOwnerIn("subagent:alpha", () -> port(ExecProperties.of(
                ExecProperties.Mode.ASK, WORKSPACE, List.of()), agent).decide(request("echo hi")))
                .approvalId();

        ApprovalDecider.Result result = new ApprovalService(jdbc,
                new ApproverRoster(Set.of(APPROVER)), agent, new JdbcExecAllowlist(jdbc), null)
                .decide(approvalId, APPROVER, ApprovalChoice.DENY, "不行");

        assertThat(result.verdict()).isEqualTo(ApprovalVerdict.DENIED);
        assertThat(result.detail()).contains("不把拒绝投回去");
        assertThat(agent.replies()).as("子 Agent 的拒绝不投递").isZero();
    }

    @Test
    @DisplayName("定时任务会话被拒：同样不投递")
    void denialForACronSessionIsNotDelivered() {
        CountingAgent agent = new CountingAgent();
        String approvalId = asOwnerIn("cron:task-1", () -> port(ExecProperties.of(
                ExecProperties.Mode.ASK, WORKSPACE, List.of()), agent).decide(request("echo hi")))
                .approvalId();

        new ApprovalService(jdbc, new ApproverRoster(Set.of(APPROVER)), agent,
                new JdbcExecAllowlist(jdbc), null)
                .decide(approvalId, APPROVER, ApprovalChoice.DENY, "不行");

        assertThat(agent.replies()).isZero();
    }

    @Test
    @DisplayName("普通会话被拒：结论投回那段对话")
    void denialForANormalSessionIsDelivered() {
        CountingAgent agent = new CountingAgent();
        String approvalId = asOwnerIn("claw4j:user:u-runner:http:chat-ap", () -> port(
                ExecProperties.of(ExecProperties.Mode.ASK, WORKSPACE, List.of()), agent)
                .decide(request("echo hi"))).approvalId();

        new ApprovalService(jdbc, new ApproverRoster(Set.of(APPROVER)), agent,
                new JdbcExecAllowlist(jdbc), null)
                .decide(approvalId, APPROVER, ApprovalChoice.DENY, "不行");

        assertThat(agent.replies()).isEqualTo(1);
        assertThat(agent.lastText()).contains("[审批被拒]").contains("没有执行");
    }

    // ------------------------------------------------------------------
    // 装配与桩
    // ------------------------------------------------------------------

    private ExecApprovalPort port(ExecProperties properties) {
        return port(properties, new CountingAgent());
    }

    private ExecApprovalPort port(ExecProperties properties, AgentPort agent) {
        return new AgentExecApprovalPort(
                new ConversationApprovalGate(new JdbcApprovalLedger(jdbc, TTL.toMillis()), List.of()),
                new JdbcExecAllowlist(jdbc), properties);
    }

    private ApprovalDecider.Result decideAsApprover(String approvalId, ApprovalChoice choice, String reason) {
        return service(ExecProperties.of(ExecProperties.Mode.ASK, WORKSPACE, List.of()))
                .decide(approvalId, APPROVER, choice, reason);
    }

    /**
     * 审批决定者的装配。
     *
     * <p>exec 配置要传：{@code allow-always} 的收不收由它判（{@code ask=always} 时不收）。
     * 传 null 的话"选以后都放行"一律被拒，而那种红会让人以为是卡片的问题。
     */
    private ApprovalDecider service(ExecProperties exec) {
        return new ApprovalService(jdbc, new ApproverRoster(Set.of(APPROVER)), new CountingAgent(),
                new JdbcExecAllowlist(jdbc), exec);
    }

    private ExecApprovalPort.Request request(String command) {
        return new ExecApprovalPort.Request(command, WORKSPACE, List.of(command), "用例");
    }

    /** 在"普通会话"里跑一段：这是工具回调那一层平时做的事（{@code ExecCallContext}）。 */
    private <T> T asOwner(java.util.function.Supplier<T> body) {
        return asOwnerIn("claw4j:user:u-runner:http:chat-ap", body);
    }

    private <T> T asOwnerIn(String sessionKey, java.util.function.Supplier<T> body) {
        Principal principal = new Principal(OWNER);
        return ExecCallContext.with(new ExecCallContext(principal, sessionKey, "http", "chat-ap", "run-1", null),
                body);
    }

    private static int pendingCount() {
        Integer count = jdbc.queryForObject(
                "select count(*) from claw4j_run.approval_request where status = 'pending'", Integer.class);
        return count == null ? 0 : count;
    }

    /** 只数"投回去了几次"的 agent 桩：这一层验的是投不投，不是投了什么。 */
    private static final class CountingAgent implements AgentPort {

        private final AtomicInteger replies = new AtomicInteger();

        private String lastText;

        @Override
        public OutboundMessage reply(InboundMessage message) {
            replies.incrementAndGet();
            lastText = message.text();
            return OutboundMessage.of(message.chatId(), "ok");
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            onChunk.accept(reply(message));
        }

        private int replies() {
            return replies.get();
        }

        private String lastText() {
            return lastText;
        }
    }
}
