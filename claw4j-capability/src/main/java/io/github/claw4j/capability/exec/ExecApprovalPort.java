package io.github.claw4j.capability.exec;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * exec 需要的"问人"这一层：查一条持久放行、发起一次审批、记住"以后这个可执行文件都放行"。
 *
 * <p><b>为什么是一个端口而不是直接调审批服务</b>：审批设施住在 agent 模块（它要落库、要发卡片），
 * exec 在能力模块，依赖方向是 agent → capability，反过来引就是环。所以这里只留三个动作，
 * agent 侧实现它、把活转给 {@code ApprovalGate}。
 *
 * <p><b>没有实现时用 {@link #denyAll()}</b>：需要人批的命令这一轮拿不到结论，于是按
 * {@code askFallback} 处置（默认拒）。这与 {@code ApprovalGate#denyAll()} 同一个取向——
 * "这个部署没开审批"的后果必须是卡住。
 *
 * <p>每个动作都拿 {@link Request}：判定需要命令、目录与拆好的段，三者缺一样，"记住这条"就落不准。
 */
public interface ExecApprovalPort {

    /**
     * 这条命令是不是已经被 {@code allow-always} 放行过。
     *
     * <p>按段查：复合命令要每一段都有条目才算批过。少一段就回落到"要问"——这正是
     * "批一次 {@code rg}，不等于批了 {@code rg && curl}"的落法。
     *
     * @param segments 拆好的命令段
     * @return 每一段都有条目时为 {@code true}
     */
    boolean alreadyApproved(List<String> segments);

    /**
     * 拿这次调用的结论：{@link Decision#redeemed} 命中过就是放行，否则登记一条并回"还没有结论"。
     *
     * <p><b>这一轮不原地等人</b>：审批靠在卡片或 HTTP 接口上点，没有"就地回答"的界面，所以
     * 每一次询问都是"先登记、这一轮拿不到结论"，由 {@link Decision.Kind#UNDECIDED} 表达。
     * 批准之后由审批服务在那段对话里开新一轮，模型重新调这个工具，那时批准命中，走的才是
     * {@link Decision.Kind#ALLOW_ONCE}。
     *
     * <p>这是与 OpenClaw 的一处形态差异：它在前台命令上等 15 秒
     * （{@code requestExecApprovalViaSocket}），因为它的操作者可能就坐在控制台前。我们的审批入口
     * 天生是异步的，等 15 秒只会把一轮对话多挂 15 秒再走同一条异步路。
     *
     * @param request 待批的这条命令
     * @return 结论；拿不到结论时是 {@link Decision.Kind#UNDECIDED}，由调用方按 {@code askFallback} 处置
     */
    Decision decide(Request request);

    /**
     * 记住"以后这个可执行文件都放行"。只在 {@link Decision.Kind#ALLOW_ALWAYS} 之后调。
     *
     * <p>哪些段落不了库由实现决定（路径形态、赋值形态都不落，见
     * {@link ExecPolicy#patternOf(String)}）——一段都落不下时整条不记，下次还问。
     *
     * @param request 刚被批准的那条命令
     */
    void remember(Request request);

    /**
     * 待批的一条命令。
     *
     * @param command  模型的原始命令
     * @param cwd      它将在哪个目录里跑
     * @param segments 拆好的段
     * @param reason   为什么要问（给人看的理由，不是判定依据）
     */
    record Request(String command, Path cwd, List<String> segments, String reason) {

        public Request {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(cwd, "cwd");
            segments = segments == null ? List.of() : List.copyOf(segments);
            reason = reason == null ? "" : reason;
        }

        /**
         * 这条命令冻结下来的计划：批准的就是它，跑的就是它。
         *
         * @return 冻结件
         */
        public FrozenPlan plan() {
            return FrozenPlan.of(command, cwd);
        }
    }

    /**
     * 一次审批的结论。
     *
     * <p>带 {@link FrozenPlan} 而不是只带一个"批了"：批准的对象是那一条具体命令。执行前拿它和
     * 现在要跑的那条逐字符比，不一致就是 <em>approval mismatch</em>——拒，而不是"差不多就跑"。
     * 批准之后改了命令还能跑，等于批准的是"某个时间点的意图"，那正是这条机制要挡的。
     *
     * @param kind       结论档位
     * @param plan       冻结件；{@link Kind#DENY} 与 {@link Kind#UNDECIDED} 时为 {@code null}
     * @param approvalId 登记上的审批编号；没登记上时为 {@code null}
     * @param detail     给人看的一句话（卡片上、工具结果里都用它）
     */
    record Decision(Kind kind, FrozenPlan plan, String approvalId, String detail) {

        public Decision {
            Objects.requireNonNull(kind, "kind");
            detail = detail == null ? "" : detail;
        }

        /** 三个结局加一个"还没有结论"，与 OpenClaw 的 {@code allow-once} / {@code allow-always} / {@code deny} 对应。 */
        public enum Kind {

            /** 只放这一次。 */
            ALLOW_ONCE,

            /** 放这一次，并记住（{@code ask=always} 时不该出现）。 */
            ALLOW_ALWAYS,

            /** 不跑。 */
            DENY,

            /** 这一轮拿不到结论：要么登记上了在等人，要么没人能批。由 {@code askFallback} 处置。 */
            UNDECIDED
        }

        /**
         * 放一次。
         *
         * @param plan 冻结件，必填
         * @return 结论
         */
        public static Decision allowOnce(FrozenPlan plan) {
            return new Decision(Kind.ALLOW_ONCE, Objects.requireNonNull(plan, "plan"), null, "");
        }

        /**
         * 放一次并记住。
         *
         * @param plan 冻结件，必填
         * @return 结论
         */
        public static Decision allowAlways(FrozenPlan plan) {
            return new Decision(Kind.ALLOW_ALWAYS, Objects.requireNonNull(plan, "plan"), null, "");
        }

        /**
         * 不跑。
         *
         * @param detail 为什么（会进工具结果）
         * @return 结论
         */
        public static Decision deny(String detail) {
            return new Decision(Kind.DENY, null, null, detail);
        }

        /**
         * 这一轮没有结论。
         *
         * @param approvalId 登记上的编号，可以为 {@code null}
         * @param detail     为什么（"已登记，编号 X" 或 "这个部署没有审批设施"）
         * @return 结论
         */
        public static Decision undecided(String approvalId, String detail) {
            return new Decision(Kind.UNDECIDED, null, approvalId, detail);
        }
    }

    /**
     * 没有审批设施时的那一档：查不到、问不到、记不下。
     *
     * <p>{@link #decide} 返回 {@code UNDECIDED} 而不是直接 {@code DENY}：因为"没有设施"与
     * "等人来不及"在语义上是同一件事（都拿不到人的结论），处置权在 {@code askFallback} 那一处。
     * 默认值 {@code deny} 让结果仍是拒——忘配置的后果必须是卡住。
     *
     * @return 一律没有结论的端口
     */
    static ExecApprovalPort denyAll() {
        return new ExecApprovalPort() {

            @Override
            public boolean alreadyApproved(List<String> segments) {
                return false;
            }

            @Override
            public Decision decide(Request request) {
                return Decision.undecided(null, "这个部署没有装配审批");
            }

            @Override
            public void remember(Request request) {
                // 没有设施可记：这次放行是一次性的，下一次仍然问——而 denyAll 连问都不问。
            }
        };
    }
}
