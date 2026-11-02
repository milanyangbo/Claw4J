package io.github.claw4j.agent.audit;

import io.github.claw4j.agent.approval.CallFingerprint;
import io.github.claw4j.agent.run.Budget;
import io.github.claw4j.agent.run.Run;
import io.github.claw4j.agent.run.RunStatus;
import io.github.claw4j.agent.run.RunStore;
import io.github.claw4j.agent.run.Step;
import io.github.claw4j.agent.run.StepAudit;
import io.github.claw4j.agent.run.StepInput;
import io.github.claw4j.agent.run.StepKind;
import io.github.claw4j.agent.run.StepStatus;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计那一行的形状：谁来、什么角色、哪个工具、什么决定、参数摘要与指纹、哪条批准（P07）。
 *
 * <p><b>为什么用假存储而不是真库</b>：这一层要验的是"该写什么"——谁的值取自哪里、
 * 摘要怎么截、指纹什么时候为空、审批编号怎么连上。库那一侧（列宽、唯一索引、视图）
 * 由 {@code RunSchemaSqlTest} 在真 Postgres 上验，两者差一层，不重复。
 *
 * <p><b>假存储只实现 {@code append}</b>：其余方法一律抛。审计只该追加一行，
 * 不读不写别的——真有一天它开始调 {@code stepsOf} 或 {@code finish}，那一条会立刻红，
 * 而不是等到某个库慢的下午才被人发现。
 *
 * <p><b>这一票最要紧的两条断言</b>：{@link #theSummaryIsTruncatedAndTheRawArgumentsNeverLand}
 * （审计不是业务数据的第二份副本）与 {@link #aGrantedApprovalIsWrittenOntoTheExecutionRow}
 * （"这次调用是哪次批准的"答得出来）。前者退化成一个 {@code text} 列、后者丢一列审批编号，
 * 都不会有任何别的用例变红。
 */
class ToolCallAuditTest {

    private static final String RUN = "run-1";

    private static final String TOOL = "querySales";

    private static final Principal CALLER =
            Principal.of("u-analyst", "analyst", Set.of("sales_order"), Set.of("read"));

    private RecordingStore runs;

    private ToolCallAudit audit;

    @BeforeEach
    void setUp() {
        runs = new RecordingStore();
        audit = new ToolCallAudit(runs);
    }

    @Test
    @DisplayName("真跑了一次：一行 tool_calls，带人、角色、工具与「执行过」这个决定")
    void anExecutedCallLandsAsOneRow() {
        audit.executed(RUN, CALLER, TOOL, "{\"region\":\"华东\"}", null);

        StepInput row = runs.only();
        assertThat(row.kind()).isEqualTo(StepKind.TOOL_CALLS);
        assertThat(row.status()).isEqualTo(StepStatus.DONE);
        assertThat(row.toolNames()).isEqualTo(TOOL);
        assertThat(row.audit().actor()).isEqualTo("u-analyst");
        assertThat(row.audit().role()).isEqualTo("analyst");
        assertThat(row.audit().outcome()).isEqualTo(StepAudit.EXECUTED);
        assertThat(row.runId()).isEqualTo(RUN);
    }

    /**
     * 审计只留摘要与指纹：完整参数不进库。
     *
     * <p>把 {@code args_summary} 换成一整列 {@code text}、或者干脆把参数原文塞进
     * {@code input}，这条会红——而那正是"审计变成业务数据第二份副本"的入口。
     */
    @Test
    @DisplayName("超长参数只留前 200 字符与一个指纹：不成为另一张业务数据副本")
    void theSummaryIsTruncatedAndTheRawArgumentsNeverLand() {
        String verbatim = "{\"filter\":\"" + "华东区".repeat(120) + "\"}";

        audit.executed(RUN, CALLER, TOOL, verbatim, null);

        StepInput row = runs.only();
        assertThat(row.input()).as("step 的 input 那一列装的是入站内容，工具参数不该钻进去").isNull();
        assertThat(row.resultSummary()).as("产出侧同样不落原文").isNull();
        assertThat(row.audit().argsSummary()).hasSize(ToolCallAudit.SUMMARY_CHARS + 1).endsWith("…");
        assertThat(verbatim).startsWith(row.audit().argsSummary().substring(0, 20));
        assertThat(row.audit().argsDigest())
                .as("指纹是 64 位十六进制：能对上审批那条，但反推不回参数")
                .isEqualTo(CallFingerprint.of(TOOL, verbatim).orElseThrow());
    }

    @Test
    @DisplayName("摘要先脱敏再截断：截在前面的号码不能因为被截断就绕过掩码")
    void theSummaryIsMaskedBeforeItIsTruncated() {
        String phone = "13800138000";

        audit.executed(RUN, CALLER, TOOL, "{\"phone\":\"" + phone + "\"}", null);

        assertThat(runs.only().audit().argsSummary()).doesNotContain(phone);
    }

    /**
     * "这次调用是哪次批准的"：兑现那一侧记下的编号必须落到执行这一行上。
     *
     * <p>而且只落一次——那条线是一次性的（{@link ToolCallAudit#granted} 的条目被取走就没了）。
     * 不这么写的话，同一轮里两次同参数调用会双双带同一个审批编号，
     * 而"一条批准只放行一次执行"这句话在审计里就成了两个答案。
     */
    @Test
    @DisplayName("兑现过的批准落到执行那一行上，而且只落一次")
    void aGrantedApprovalIsWrittenOntoTheExecutionRow() {
        String args = "{\"to\":\"zhang@example.com\"}";
        String digest = CallFingerprint.of("sendEmail", args).orElseThrow();

        audit.granted(RUN, "sendEmail", digest, "approval-7");
        audit.executed(RUN, CALLER, "sendEmail", args, null);
        audit.executed(RUN, CALLER, "sendEmail", args, null);

        assertThat(runs.rows).hasSize(2);
        assertThat(runs.rows.get(0).audit().approvalId()).isEqualTo("approval-7");
        assertThat(runs.rows.get(1).audit().approvalId())
                .as("第二条同参数调用不该再搭上那条批准")
                .isNull();
    }

    @Test
    @DisplayName("兑换记在别的 run 上不串味：键里带 runId")
    void aGrantDoesNotLeakIntoAnotherRun() {
        String args = "{\"to\":\"zhang@example.com\"}";
        String digest = CallFingerprint.of("sendEmail", args).orElseThrow();

        audit.granted("run-1", "sendEmail", digest, "approval-7");
        audit.executed("run-2", CALLER, "sendEmail", args, null);

        assertThat(runs.only().audit().approvalId()).isNull();
    }

    @Test
    @DisplayName("被拒：一行 rejected，带工具名与角色，理由是回给模型的那句话")
    void aRejectedCallLandsWithItsReason() {
        audit.rejected(RUN, CALLER, TOOL, "{\"region\":\"华东\"}",
                "没有权限：角色 analyst 不允许访问表 sales_order（工具 querySales）。");

        StepInput row = runs.only();
        assertThat(row.audit().outcome()).isEqualTo(StepAudit.REJECTED);
        assertThat(row.status()).isEqualTo(StepStatus.FAILED);
        assertThat(row.toolNames()).isEqualTo(TOOL);
        assertThat(row.audit().role()).isEqualTo("analyst");
        assertThat(row.resultSummary()).startsWith("没有权限").contains("sales_order");
        assertThat(row.audit().approvalId()).isNull();
    }

    @Test
    @DisplayName("停在等审批：一行 awaiting_approval，带着审批编号，状态不是「等审批」")
    void anAwaitingCallLandsWithTheApprovalId() {
        audit.awaitingApproval(RUN, CALLER, "sendEmail", "{\"to\":\"zhang@example.com\"}",
                "approval-9", "这个动作要先审批：工具 sendEmail 会执行 send_email 动作。");

        StepInput row = runs.only();
        assertThat(row.audit().outcome()).isEqualTo(StepAudit.AWAITING_APPROVAL);
        assertThat(row.audit().approvalId()).isEqualTo("approval-9");
        assertThat(row.status())
                .as("等审批这个状态属于 approval 那一类 step，它会被结论改掉；"
                        + "这一行记的是「这次调用没发生」，不该跟着变")
                .isEqualTo(StepStatus.FAILED);
    }

    /**
     * 工具自己抛了：那是"跑过但失败了"，与"被拦住"是两件事——前者修工具，后者改配置。
     *
     * <p>只记异常类型不记 message：message 里常有数据（一条贴了 SQL 的报错就够搬走一张表）。
     */
    @Test
    @DisplayName("工具抛异常：executed + failed，只记类型不记 message")
    void aFailingToolIsStillAnExecution() {
        audit.executed(RUN, CALLER, TOOL, "{}", "IllegalStateException");

        StepInput row = runs.only();
        assertThat(row.audit().outcome()).isEqualTo(StepAudit.EXECUTED);
        assertThat(row.status()).isEqualTo(StepStatus.FAILED);
        assertThat(row.resultSummary()).isEqualTo("工具抛出 IllegalStateException");
    }

    @Test
    @DisplayName("参数收不全（流式被拦在授权那一道）：指纹留空，摘要照记")
    void aHalfReceivedArgumentLeavesNoDigest() {
        audit.rejected(RUN, CALLER, TOOL, "{\"region\":", "没有权限：角色 analyst 不允许执行 read 动作。");

        StepInput row = runs.only();
        assertThat(row.audit().argsDigest()).isNull();
        assertThat(row.audit().argsSummary()).isEqualTo("{\"region\":");
    }

    @Test
    @DisplayName("不在任何一个 run 里：不写（没有可挂的落点），也不抛")
    void nothingIsWrittenOutsideARun() {
        assertThatCode(() -> audit.executed(null, CALLER, TOOL, "{}", null)).doesNotThrowAnyException();
        assertThatCode(() -> audit.rejected(" ", CALLER, TOOL, "{}", "没有权限"))
                .doesNotThrowAnyException();

        assertThat(runs.rows).isEmpty();
    }

    @Test
    @DisplayName("没有 run 存储：整体空转，判定不受影响")
    void withoutAStoreItSimplyWritesNothing() {
        ToolCallAudit withoutStore = new ToolCallAudit(null);

        assertThatCode(() -> withoutStore.executed(RUN, CALLER, TOOL, "{}", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> withoutStore.rejected(RUN, null, TOOL, "{}", "没有权限"))
                .doesNotThrowAnyException();
    }

    /**
     * 存储写的时候炸了（不是降级成空值，而是真抛）：仍然不该让一次工具调用失败。
     *
     * <p>审计是记账，不是回答的前置条件——与 {@code JdbcRunStore} 那句
     * "库没迁也不能把提问打成 500" 是同一个判断，只是这里连它自己那一层也兜住了。
     */
    @Test
    @DisplayName("写审计时抛异常：吞掉，不影响这次工具调用")
    void aBrokenStoreNeverBreaksTheCall() {
        ToolCallAudit broken = new ToolCallAudit(new ThrowingStore());

        assertThatCode(() -> broken.executed(RUN, CALLER, TOOL, "{}", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("决定只有三种：编一个第四种在构造时就被拒，而不是等库里的 check 报错")
    void anUnknownOutcomeIsRejectedBeforeItReachesTheDatabase() {
        assertThatThrownBy(() -> new StepAudit("u-1", "analyst", "denied", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("决定");
    }

    // ---------------------------------------------------------------- 假存储

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
        public Optional<Budget> spent(String runId) {
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
}
