package io.github.claw4j.agent.session;

import io.github.claw4j.agent.approval.CallFingerprint;
import io.github.claw4j.agent.audit.AuditAction;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.AuditStatus;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.run.Budget;
import io.github.claw4j.agent.run.Run;
import io.github.claw4j.agent.run.RunStatus;
import io.github.claw4j.agent.run.RunStore;
import io.github.claw4j.agent.run.Step;
import io.github.claw4j.agent.run.StepAudit;
import io.github.claw4j.agent.run.StepInput;
import io.github.claw4j.agent.run.StepKind;
import io.github.claw4j.agent.run.StepStatus;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具这一层的两端：**进来时这个身份能不能用**，以及**出去之后系统记下了什么**。
 *
 * <p><b>为什么这两半在一个文件里</b>：它们问的是同一个问题的两面——"一次工具调用由谁决定、
 * 又给下一次追问留下了什么"。召回那一半的边界是"别把别人的话带回来"（
 * {@link #anotherUserSeesNothing}、{@link #downgradedUserLosesItsOwnHistory}），
 * 审计那一半的边界是"别把业务数据抄成第二份"（
 * {@link #theSummaryIsTruncatedAndTheRawArgumentsNeverLand}）。
 * 而 {@code claw4j-agent} 的测试文件位已经用满 10 个，合成一处与两边都不冲突。
 * 放在 {@code session} 包里是不得已：{@link RecallSessionTool#MAX_RESULT_CHARS} 是包内可见的，
 * 挪到别的包就只能把它改成 public——那是为了测试放宽生产代码。
 *
 * <p><b>审计那一半为什么用假存储而不是真库</b>：这一层要验的是"该写什么"——谁的值取自哪里、
 * 摘要怎么截、指纹什么时候为空、审批编号怎么连上。库那一侧（列宽、唯一索引、视图）
 * 由 {@code RunSchemaSqlTest} 在真 Postgres 上验，两者差一层，不重复。
 *
 * <p><b>假存储只实现 {@code append}</b>：其余方法一律抛。审计只该追加一行，
 * 不读不写别的——真有一天它开始调 {@code stepsOf} 或 {@code finish}，那一条会立刻红，
 * 而不是等到某个库慢的下午才被人发现。
 */
class ToolGovernanceTest {

    // ---------------------------------------------------------------- 审计那一半（ADR-0027）

    private static final String RUN = "run-1";

    private static final String SESSION = "claw4j:user:u-analyst@fp-1:web:chat-a";

    private static final String TOOL = "querySales";

    private static final Principal CALLER =
            Principal.of("u-analyst", "analyst", Set.of("sales_order"), Set.of("read"));

    /** 真正落下来的事件。审计的形状变了（ADR-0027）：断言看事件，不看 step 行。 */
    private List<AuditEvent> events;

    private ToolCallAudit audit;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        audit = new ToolCallAudit(event -> {
            events.add(event);
            return Optional.of((long) events.size());
        });
    }

    private static AuditEvent.Scope scope() {
        return new AuditEvent.Scope(RUN, SESSION, null, "u-analyst", "analyst", "web");
    }

    @Test
    @DisplayName("真跑了一次：两条事件（开始 + 成功），同一次调用指纹相同")
    void anExecutedCallLandsAsTwoEvents() {
        audit.toolStarted(scope(), TOOL, "{\"region\":\"华东\"}");
        audit.toolFinished(scope(), TOOL, "{\"region\":\"华东\"}", null);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).action()).isEqualTo(AuditAction.TOOL_ACTION_STARTED);
        assertThat(events.get(1).action()).isEqualTo(AuditAction.TOOL_ACTION_FINISHED);
        assertThat(events.get(1).status()).isEqualTo(AuditStatus.SUCCEEDED);
        assertThat(events.get(0).callFingerprint())
                .as("两条是同一次调用：指纹算的是同一份参数")
                .isNotNull()
                .isEqualTo(events.get(1).callFingerprint());
        assertThat(events.get(1).scope().actor()).isEqualTo("u-analyst");
        assertThat(events.get(1).scope().actorRole()).isEqualTo("analyst");
        assertThat(events.get(1).toolName()).isEqualTo(TOOL);
    }

    /**
     * 参数原文与摘要都不进事件。
     *
     * <p>把摘要当"给人看的一格"加回来，这条会红——而它正是"审计变成业务数据第二份副本"的入口。
     * OpenClaw 的原话是 never stores prompts, messages, tool arguments, tool results。
     */
    @Test
    @DisplayName("参数原文与摘要都不进事件：审计不成为业务数据的第二份副本")
    void theRawArgumentsNeverLand() {
        String verbatim = "{\"phone\":\"13800138000\",\"note\":\"" + "华东区".repeat(120) + "\"}";

        audit.toolStarted(scope(), TOOL, verbatim);
        audit.toolFinished(scope(), TOOL, verbatim, null);

        assertThat(events).allSatisfy(event -> {
            assertThat(event.toolName()).isEqualTo(TOOL);
            assertThat(event.errorCode()).isNull();
            assertThat(event.approvalId()).isNull();
            assertThat(event.scope().sessionKey()).isEqualTo(SESSION);
        });
        assertThat(events.toString())
                .as("指纹在，原文不在：一条贴了号码的参数不该整段进库")
                .doesNotContain("13800138000")
                .doesNotContain("华东区");
    }

    /**
     * "这次调用是哪次批准的"：兑现那一侧记下的编号必须落到执行事件上。
     *
     * <p>三条断言各拦一种写法：① 编号根本不落；② 同一轮里两次同参数调用双双带上同一个编号；
     * ③ 键里不带 {@code runId}（批准从另一轮漏过来，看起来完全正常）。
     */
    @Test
    @DisplayName("兑现过的批准只落在它自己那一轮的执行事件上，而且只落一次")
    void aGrantLandsOnlyOnItsOwnExecution() {
        String args = "{\"to\":\"zhang@example.com\"}";
        String digest = CallFingerprint.of("sendEmail", args).orElseThrow();

        audit.granted(RUN, "sendEmail", digest, "approval-7");
        audit.toolFinished(scope(), "sendEmail", args, null);
        audit.toolFinished(scope(), "sendEmail", args, null);
        audit.toolFinished(new AuditEvent.Scope("run-2", SESSION, null, "u-analyst", "analyst", "web"),
                "sendEmail", args, null);

        assertThat(events).hasSize(3);
        assertThat(events.get(0).approvalId()).isEqualTo("approval-7");
        assertThat(events.get(1).approvalId())
                .as("第二条同参数调用不该再搭上那条批准")
                .isNull();
        assertThat(events.get(2).approvalId())
                .as("兑换记在别的 run 上不串味：键里带 runId")
                .isNull();
    }

    @Test
    @DisplayName("被拒：一条 blocked，没有「开始」那一条，理由是错误码不是文案")
    void aRejectedCallIsOneBlockedEvent() {
        audit.rejected(scope(), TOOL, "{\"region\":\"华东\"}");

        assertThat(events).hasSize(1);
        AuditEvent event = events.getFirst();
        assertThat(event.action()).isEqualTo(AuditAction.TOOL_ACTION_FINISHED);
        assertThat(event.status()).isEqualTo(AuditStatus.BLOCKED);
        assertThat(event.errorCode()).isEqualTo(AuditEvent.POLICY_DENIED);
        assertThat(event.toolName()).isEqualTo(TOOL);
        assertThat(event.scope().actorRole()).isEqualTo("analyst");
    }

    /**
     * 等审批一条都不写。
     *
     * <p>这一条守的是"同一个事实只在一处"：谁提的、批没批、谁批的，都在 {@code approval_request}
     * 那一行上。审计再记一遍，第一个代价是"今天有几条被拒"要 union 两张表，
     * 第二个代价是两张迟早对不上。
     */
    @Test
    @DisplayName("等审批：一条审计事件都不写——那件事的真相在 approval_request 上")
    void anAwaitingCallWritesNothing() {
        audit.granted(RUN, "sendEmail", "digest", "approval-9");

        assertThat(events).as("兑现只是记一条待配对的线索，不是一条事件").isEmpty();
    }

    @Test
    @DisplayName("工具抛异常：结束事件是 failed + tool_error，异常类型名不进库")
    void aFailingToolIsStillAnExecution() {
        audit.toolFinished(scope(), TOOL, "{}", "IllegalStateException");

        AuditEvent event = events.getFirst();
        assertThat(event.status()).isEqualTo(AuditStatus.FAILED);
        assertThat(event.errorCode()).isEqualTo(AuditEvent.TOOL_ERROR);
        assertThat(event.toString())
                .as("类型名是开放的，而 error_code 那一列是闭合口径；要查类型看 trace")
                .doesNotContain("IllegalStateException");
    }

    @Test
    @DisplayName("参数收不全（流式被拦在授权那一道）：指纹留空，事件照写")
    void aHalfReceivedArgumentLeavesNoFingerprint() {
        audit.rejected(scope(), TOOL, "{\"region\":");

        assertThat(events.getFirst().callFingerprint()).isNull();
        assertThat(events.getFirst().status()).isEqualTo(AuditStatus.BLOCKED);
    }

    @Test
    @DisplayName("没有 runId 也写：「发生过」比字段齐全重要，只是那一格空")
    void anEventWithoutARunIsStillWritten() {
        audit.toolFinished(AuditEvent.Scope.empty(), TOOL, "{}", null);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().scope().runId()).isNull();
        assertThat(events.getFirst().toolName()).isEqualTo(TOOL);
    }

    @Test
    @DisplayName("没有账本：整体空转，判定不受影响")
    void withoutALedgerItSimplyWritesNothing() {
        ToolCallAudit withoutLedger = new ToolCallAudit(null);

        assertThatCode(() -> withoutLedger.toolFinished(scope(), TOOL, "{}", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> withoutLedger.rejected(scope(), TOOL, "{}"))
                .doesNotThrowAnyException();
    }

    /**
     * 账本写的时候炸了：仍然不该让一次工具调用失败。审计是记账，不是回答的前置条件。
     */
    @Test
    @DisplayName("写审计时抛异常：吞掉，不影响这次工具调用")
    void aBrokenLedgerNeverBreaksTheCall() {
        ToolCallAudit broken = new ToolCallAudit(event -> {
            throw new IllegalStateException("库挂了");
        });

        assertThatCode(() -> broken.toolFinished(scope(), TOOL, "{}", null))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 召回那一半（只在本段对话里）

    /** 这个身份在一条通道的一个 chat 里。键一律由 {@link SessionKeys} 派生，不手拼。 */
    private static String sessionOf(String userId, String channel, String chatId) {
        return SessionKeys.sessionIdOf(Principal.readOnly(userId), channel, chatId);
    }

    /**
     * 当前这一段对话。<b>带段</b>（ADR-0026）：召回的身份校验是前缀匹配
     * （{@code startsWith(PREFIX + owner + ":")}），段拼在最后不能影响它。
     *
     * <p>故意不给这个常量一个"无段"的形状：如果哪天有人把段拼到 owner 前面，
     * 下面几条召回用例里"换一个人就看不到"会先红——那正是把越权读变成报错的唯一办法。
     */
    private static final String CURRENT_SESSION = SessionKeys.sessionIdOf(
            Principal.readOnly("u-1"), "web", "chat-a", "1762300000000-abcd");

    /** 同一个人的**另外两段**对话：网页上的另一个 chat，以及一个项目群。 */
    private static final String PRIVATE_ELSEWHERE = sessionOf("u-1", "web", "chat-b");

    private static final String GROUP_ELSEWHERE = sessionOf("u-1", "feishu", "oc_group");

    /**
     * 在假仓库里登记一段会话，归属键从会话 id 的前缀取。
     *
     * <p><b>为什么每次都要登记</b>：真库里 {@code findEventsByUserId} 靠 {@code ai_session.user_id}
     * 把事件找出来；假仓库照抄了这条路径。不登记的话，"改回按 user_id 查"这个反例
     * 会因为假仓库什么都没有而红，而不是因为泄露——那种红是假红，它挡不住真正的回退。
     */
    private static void open(FakeSessions sessions, String sessionId) {
        int from = SessionKeys.PREFIX.length();
        int to = sessionId.indexOf(SessionKeys.SESSION_SEPARATOR, from);
        sessions.create(CreateSessionRequest.builder()
                .id(sessionId)
                .userId(sessionId.substring(from, to))
                .build());
    }

    @Test
    @DisplayName("只召回本段对话（含压缩归档过的）：同一个人的私聊与群聊都带不回来")
    void recallsOnlyThisConversation() {
        FakeSessions sessions = new FakeSessions();
        open(sessions, CURRENT_SESSION);
        open(sessions, PRIVATE_ELSEWHERE);
        open(sessions, GROUP_ELSEWHERE);
        sessions.append(CURRENT_SESSION, "华东区的成交口径按签约主体算，不按开票主体");
        sessions.appendArchived(CURRENT_SESSION, "华东区那批单子按出库时间归月，不看开票日");
        sessions.append(PRIVATE_ELSEWHERE, "华东上周成交额 128,400.00，先别外传");
        sessions.append(GROUP_ELSEWHERE, "群里说过：华东那边先按开票主体报");

        String recalled = tool(sessions).call("{\"query\":\"华东\"}",
                toolContext(Principal.readOnly("u-1"), CURRENT_SESSION));

        assertThat(recalled).contains("按签约主体算");
        assertThat(recalled)
                .as("归档过的那句也在：压缩把原文折成摘要之后，它是这个工具唯一的价值来源")
                .contains("按出库时间归月");
        assertThat(recalled)
                .as("私聊里的数字被带进群聊，就是一次没人复核的泄露——听众不一样，权限指纹挡不住")
                .doesNotContain("128,400")
                .doesNotContain("先按开票主体报");
    }

    @Test
    @DisplayName("match_mode 真的传下去了：all 只留同时命中全部关键词的那段")
    void matchModeNarrowsTheResults() {
        FakeSessions sessions = new FakeSessions();
        open(sessions, CURRENT_SESSION);
        sessions.append(CURRENT_SESSION, "华东区的成交口径按签约主体算");
        sessions.append(CURRENT_SESSION, "华南那边的口径还是按开票主体");

        String onlyBoth = tool(sessions).call("{\"query\":\"华东,口径\",\"match_mode\":\"all\"}",
                toolContext(Principal.readOnly("u-1"), CURRENT_SESSION));

        assertThat(onlyBoth).contains("按签约主体算").doesNotContain("华南");
    }

    @Test
    @DisplayName("换一个身份就看不到：这段会话不属于他就拒，不退化成照样查")
    void anotherUserSeesNothing() {
        FakeSessions sessions = new FakeSessions();
        open(sessions, CURRENT_SESSION);
        sessions.append(CURRENT_SESSION, "华东区的成交口径按签约主体算");

        String recalled = tool(sessions).call("{\"query\":\"华东\"}",
                toolContext(Principal.readOnly("u-2"), CURRENT_SESSION));

        assertThat(recalled)
                .as("u-2 拿到了 u-1 那一段的会话 id 也查不出东西——这是这个工具唯一的边界")
                .doesNotContain("签约主体")
                .contains("不属于当前身份");
    }

    @Test
    @DisplayName("同一个人被降权后，旧会话召不回来：会话键里带着权限指纹")
    void downgradedUserLosesItsOwnHistory() {
        FakeSessions sessions = new FakeSessions();
        open(sessions, CURRENT_SESSION);
        sessions.append(CURRENT_SESSION, "上周华东区成交额 99,999.00");

        Principal downgraded = Principal.of("u-1", "guest",
                Set.of("region"), Set.of(ToolPermission.READ));

        String recalled = tool(sessions).call("{\"query\":\"成交额\"}",
                toolContext(downgraded, CURRENT_SESSION));

        assertThat(recalled)
                .as("降权之后还念得出高权限时看到的数字，权限就等于没收回去")
                .doesNotContain("99,999")
                .contains("不属于当前身份");
    }

    @Test
    @DisplayName("缺身份或缺口令就不查：宁可不回答，也不猜成「某个人的所有会话」")
    void refusesWithoutIdentityOrSession() {
        FakeSessions sessions = new FakeSessions();
        open(sessions, CURRENT_SESSION);
        sessions.append(CURRENT_SESSION, "华东区的成交口径按签约主体算");

        assertThat(tool(sessions).call("{\"query\":\"华东\"}", new ToolContext(
                Map.of(ChatMemory.CONVERSATION_ID, CURRENT_SESSION))))
                .contains("没有携带身份")
                .doesNotContain("签约主体");
        assertThat(tool(sessions).call("{\"query\":\"华东\"}"))
                .as("单参入口没有 ToolContext，同样按没身份处理")
                .contains("没有携带身份");
        assertThat(tool(sessions).call("{\"query\":\"华东\"}",
                new ToolContext(Map.of(ContextKeys.PRINCIPAL, Principal.readOnly("u-1")))))
                .as("按 principal 猜会话，就是改回按 user_id 查：那正是私聊漏进群聊的那条路")
                .contains("没有携带会话标识")
                .doesNotContain("签约主体");
    }

    @Test
    @DisplayName("一条长对话不会把上下文吃满：条数与总字数都封顶")
    void truncatesWhatItBringsBack() {
        FakeSessions sessions = new FakeSessions();
        open(sessions, CURRENT_SESSION);
        for (int i = 0; i < 40; i++) {
            sessions.append(CURRENT_SESSION, "华东口径第 " + i + " 段：" + "按签约主体算。".repeat(60));
        }

        String recalled = tool(sessions).call("{\"query\":\"华东口径\"}",
                toolContext(Principal.readOnly("u-1"), CURRENT_SESSION));

        assertThat(recalled.length())
                .as("条数封顶后仍可能是一条一万字，整段必须再按字符裁一次")
                .isLessThan(RecallSessionTool.MAX_RESULT_CHARS + 200);
        assertThat(recalled).contains("已截断");
    }

    @Test
    @DisplayName("参数写错了回一句话，不是抛出去变成整轮 500")
    void badArgumentsComeBackAsAdvice() {
        FakeSessions sessions = new FakeSessions();
        RecallSessionTool tool = tool(sessions);
        ToolContext context = toolContext(Principal.readOnly("u-1"), CURRENT_SESSION);

        assertThat(tool.call("{\"match_mode\":\"all\"}", context))
                .as("query 必填")
                .contains("召回参数不合法");
        assertThat(tool.call("{\"query\":\"华东\",\"since\":\"上周\"}", context))
                .contains("召回参数不合法");
        assertThat(tool.call("{\"query\":\"华东\",\"page\":\"第一页\"}", context))
                .contains("召回参数不合法");
        assertThat(tool.call("{\"query\":\"华东\",\"match_mode\":\"大部分\"}", context))
                .contains("召回参数不合法");
    }

    @Test
    @DisplayName("权限是 recall_session，不带表；只读能用、guest 不能用")
    void permissionIsItsOwnAction() {
        ToolPermission permission = RecallSessionTool.permission();

        assertThat(permission.action()).isEqualTo("recall_session");
        assertThat(permission.tables()).as("它读的是会话，不是业务表").isEmpty();
        assertThat(readOnlyWithRecall("u-1").canUse(permission))
                .as("read-only 不给就等于只有管理员能回忆，这个能力等于没上")
                .isTrue();
        assertThat(Principal.readOnly("u-1").canUse(permission))
                .as("内置的只读身份只有 read，它本来就不含这个能力——放行与否由角色配置里那一行决定")
                .isFalse();
        assertThat(Principal.of("u-3", "guest", Set.of("region"), Set.of(ToolPermission.READ))
                .canUse(permission))
                .as("guest 的 actions 里没有 recall_session：能读 region 与能回忆自己说过什么是两件事")
                .isFalse();
    }

    // ---------------------------------------------------------------- 假存储 / 假会话

    /** 只认 {@code append}：审计只该追加一行，其余方法被调到就是它越界了。 */
    private static class RecordingStore implements RunStore {

        private final List<StepInput> rows = new ArrayList<>();

        @Override
        public Optional<Step> append(StepInput input) {
            rows.add(input);
            return Optional.of(new Step(input.runId(), rows.size(), input.kind(), input.input(),
                    input.toolNames(), input.resultSummary(), input.status(), null));
        }

        private StepInput only() {
            assertThat(rows).as("这一轮应当正好落一行审计").hasSize(1);
            return rows.getFirst();
        }

        @Override
        public Optional<Run> start(String owner, String principalDigest, String goal,
                                   NotifyPolicy notifyPolicy) {
            throw new UnsupportedOperationException("审计不该开 run");
        }

        @Override
        public void attachPrincipal(String runId, Principal principal) {
            throw new UnsupportedOperationException("审计不该写发起人快照");
        }

        @Override
        public Optional<Run> resume(String runId, String owner) {
            throw new UnsupportedOperationException("审计不该接 run");
        }

        @Override
        public Optional<Run> reopen(String runId) {
            throw new UnsupportedOperationException("审计不该续跑");
        }

        @Override
        public void finish(String runId, RunStatus status, String result) {
            throw new UnsupportedOperationException("审计不该收尾");
        }

        @Override
        public void suspend(String runId) {
            throw new UnsupportedOperationException("审计不该挂起");
        }

        @Override
        public Optional<Principal> principalOf(String runId) {
            throw new UnsupportedOperationException("审计不该读身份");
        }

        @Override
        public void settleApprovalStep(String runId, StepStatus status) {
            throw new UnsupportedOperationException("审计不该改审批那一步");
        }

        @Override
        public Optional<Run> find(String runId) {
            throw new UnsupportedOperationException("审计不该读 run");
        }

        @Override
        public List<Step> stepsOf(String runId) {
            throw new UnsupportedOperationException("审计不该回读 step");
        }

        @Override
        public List<Step> planOf(String runId) {
            throw new UnsupportedOperationException("审计不该读计划");
        }

        @Override
        public Optional<List<Step>> setPlan(String runId, List<String> items) {
            throw new UnsupportedOperationException("审计不该写计划");
        }

        @Override
        public Optional<Budget> spent(String runId, java.time.Instant idleBefore) {
            throw new UnsupportedOperationException("审计不该读用量");
        }

        @Override
        public Optional<Budget> charge(String runId, int toolCalls, long tokens, long millis) {
            throw new UnsupportedOperationException("审计不该记用量");
        }

        @Override
        public Optional<Step> markPlan(String runId, int ordinal, StepStatus status) {
            throw new UnsupportedOperationException("审计不该标计划");
        }
    }

    /** 写的时候真抛的那种存储：验"兜住了没有"。其余方法与上面那个假存储同源，所以直接继承。 */
    private static final class ThrowingStore extends RecordingStore {

        @Override
        public Optional<Step> append(StepInput input) {
            throw new IllegalStateException("库连不上");
        }
    }

    /** 与 application.yml 里 read-only 那一行同形状：read 之外还给了 recall_session。 */
    private static Principal readOnlyWithRecall(String userId) {
        return Principal.of(userId, "read-only",
                Set.of(Principal.WILDCARD),
                Set.of(ToolPermission.READ, RecallSessionTool.ACTION));
    }

    private static RecallSessionTool tool(SessionService sessions) {
        return new RecallSessionTool(sessions);
    }

    private static ToolContext toolContext(Principal principal, String sessionId) {
        return new ToolContext(Map.of(
                ContextKeys.PRINCIPAL, principal,
                // 宿主（ChatClientAgentPort）写进去的那一把键，工具只认这一个。
                ChatMemory.CONVERSATION_ID, sessionId));
    }

    /**
     * 假会话仓库：只做两件事——按 userId 找事件、按框架的 {@link EventFilter} 过滤。
     *
     * <p>过滤交给框架自己的 {@code matches}，不自己写字符串包含：
     * 自己实现一遍的话，测的就是"我以为的关键词匹配"，而真跑起来语义可能完全不同
     * （大小写、多词组合、归档事件剔不剔），这种测试在换实现时一声不响地继续绿。
     */
    private static final class FakeSessions implements SessionService {

        private final Map<String, Session> sessions = new LinkedHashMap<>();

        private final Map<String, List<SessionEvent>> events = new LinkedHashMap<>();

        private int eventSequence;

        @Override
        public Session create(CreateSessionRequest request) {
            Session session = Session.builder()
                    .id(request.id())
                    .userId(request.userId())
                    .createdAt(Instant.now())
                    .build();
            sessions.put(session.id(), session);
            return session;
        }

        void append(String sessionId, String text) {
            append(sessionId, text, false);
        }

        /** 压缩归档过的事件：{@code excludeArchived} 默认 false，所以它们必须还能被召回。 */
        void appendArchived(String sessionId, String text) {
            append(sessionId, text, true);
        }

        private void append(String sessionId, String text, boolean archived) {
            events.computeIfAbsent(sessionId, key -> new ArrayList<>())
                    .add(SessionEvent.builder()
                            .id("e-" + (++eventSequence))
                            .sessionId(sessionId)
                            .timestamp(Instant.now().plusSeconds(eventSequence))
                            .message(new UserMessage(text))
                            .archived(archived)
                            .build());
        }

        @Override
        public Session findById(String id) {
            return sessions.get(id);
        }

        @Override
        public List<Session> findByUserId(String userId) {
            return sessions.values().stream().filter(s -> s.userId().equals(userId)).toList();
        }

        @Override
        public void delete(String id) {
            sessions.remove(id);
            events.remove(id);
        }

        @Override
        public int deleteExpiredSessions(Instant now) {
            return 0;
        }

        @Override
        public void appendEvent(SessionEvent event) {
            events.computeIfAbsent(event.getSessionId(), key -> new ArrayList<>()).add(event);
        }

        @Override
        public List<SessionEvent> getEvents(String sessionId, EventFilter filter) {
            return filter.apply(events.getOrDefault(sessionId, List.of()));
        }

        @Override
        public List<SessionEvent> findEventsByUserId(String userId, EventFilter filter) {
            List<SessionEvent> mine = sessions.values().stream()
                    .filter(session -> userId.equals(session.userId()))
                    .flatMap(session -> events.getOrDefault(session.id(), List.of()).stream())
                    .toList();
            return filter.apply(mine);
        }

        /** 压缩与本票无关：测试不压，但接口要求有实现。 */
        @Override
        public CompactionResult compact(String sessionId, CompactionTrigger trigger, CompactionStrategy strategy) {
            return null;
        }
    }
}
