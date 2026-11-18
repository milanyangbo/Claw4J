package io.github.claw4j.domain.port;

import io.github.claw4j.domain.principal.Principal;

import java.time.Instant;
import java.util.Optional;

/**
 * 审批账本：这个人此刻针对这一次具体要干的事，有没有一条仍然有效的批准（P01、P02）。
 *
 * <p>它回答"有没有"，不回答"批不批"：批是一次人的决定，发生在这条调用链之外（HTTP 接口、
 * 飞书卡片，P03）。鉴权这一端只可能在工具执行之前问一句"有人批过吗"，所以端口收窄到两件事
 * ——问一句、记一条，把"等人点、超时作废"留给 {@link ApprovalDecider}，鉴权 Advisor 就仍然
 * 只是一个做判定的组件。
 *
 * <p>口径从"工具"细化到"工具 + 参数"（P02）。P01 只按工具名问，于是"批了查一次上周成交额"
 * 也放行了"查全表"。加进参数指纹之后，批准的对象是"这一次具体会跑起来的那个调用"——参数变了
 * 就是另一次，要重新批。
 *
 * <p>指纹的形状是 {@code sha256(工具名 + 规范化参数)}，不含 runId 也不含序号。这与幂等键
 * （ADR-0017）刚好相反：那个要区分每一次执行，这个要跨执行复用一次批准。两个哈希混用的事故
 * 是双向的：用幂等键当批准键，每换一件事都要重新批；用批准键当幂等键，同一件事里第二次同参数
 * 调用会被当成"已经做过"而跳过。
 *
 * <p>没有批准是一条正常答案，不是错误：查不到就是"没有"，调用方照着拒绝走。读失败时也一样，
 * 见 {@code JdbcApprovalLedger}：读不到按"没有"处理，宁可拦住一个本来批过的动作，也不放过
 * 一个没人批过的。
 *
 * <p>实现在 Agent 模块（审批表与审计在同一个 schema 下），装配在
 * {@code AgentPersistenceConfiguration}；没有装配时鉴权那一层退到
 * {@code ApprovalGate#denyAll()}——"没有审批设施"的失败姿势是拦住，不是放行。这里不提供
 * {@code empty()} 那种空实现：{@code domain.port} 只放契约（{@code CoreBoundariesTest} 会红），
 * 而"没有审批时怎么办"是装配侧的决定。
 */
public interface ApprovalLedger {

    /**
     * 一条仍然有效的批准。
     *
     * @param id        审批记录的 id。拒绝话术与审计都要能指回具体那一条
     * @param expiresAt 批到什么时候。过期与否由实现判定（{@code expires_at > now()}），
     *                  调用方拿到的每一条都已经在有效期内
     * @param frozenPlan 批准那一刻冻结下来的执行计划（JSON，形状由实现与应用侧约定），
     *                  可以为 {@code null}：早期记录没有这一列，或者这条批准不是 exec 落下的。
     *                  **它是"批的是哪一条命令"的唯一凭据**——执行前拿它和现在要跑的逐字符比，
     *                  不一致就拒，而不是重算一遍参数再比指纹
     */
    record Grant(String id, Instant expiresAt, String frozenPlan) {

        /**
         * 没有冻结件的批准：除 exec 之外的审批（工具名 + 参数指纹）走这一档。
         *
         * @param id        审批记录的 id
         * @param expiresAt 批到什么时候
         */
        public Grant(String id, Instant expiresAt) {
            this(id, expiresAt, null);
        }
    }

    /**
     * 一次"我想干这件事，但没人批过"的登记。
     *
     * @param runId       这条请求是哪一轮发起的。可以为 null（子 Agent 那一层还没有编号，A10），
     *                    为空时审批照样成立，只是事后答不出"是哪一轮发起的"
     * @param owner       发起这次调用的人
     *                    （{@link io.github.claw4j.domain.principal.Principal#userId()}）。批准是
     *                    批给某个人的，不是批给某个动作：同一句话换个没被批过的人来问，仍然要
     *                    重新批一遍
     * @param toolName    要执行的工具，不是动作名——动作是权限的口径（{@code send_email}），
     *                    批准的对象则是"这一次具体会跑起来的那个工具"
     * @param argsDigest  参数指纹（{@link #redeem} 的同一个口径）。它把批准从"这个工具"收窄到
     *                    "这个工具的这一种调用方式"
     * @param argsSummary 给人看的参数摘要（已脱敏、已截断）。审批人看不到自己批的是什么时，
     *                    点下去的那个按钮等于盖章
     * @param origin      这条请求从哪段对话来的（ADR-0027）。批准之后就在那段对话里开新一轮，
     *                    而不用拿 {@code runId} 去唤醒一件"挂着的事"。为 null 表示这一层还没有
     *                    对话（子 Agent，A10）——那时结论落得下来，但没有"接着做"这回事
     * @param frozenPlan  批准之后要跑的那份计划（JSON）：exec 用它，除 exec 之外的审批传 {@code null}。
     *                    存下来才答得出"批准的是哪一条命令"，也才做得了 approval mismatch 的判断
     * @param allowAlways 这个请求接不接受"以后都放行"（票 05）。为 {@code false} 时卡片上不给那一档，
     *                    服务端也拒收。{@code ask=always} 下必须是 {@code false}——那一档的含义是
     *                    每条都要人看
     */
    record Ask(String runId, String owner, String toolName, String argsDigest, String argsSummary,
               Origin origin, String frozenPlan, boolean allowAlways) {

        /**
         * 不接受"以后都放行"的那一档：没有持久放行落点的审批都走它。
         *
         * @param runId       哪一轮发起的，可以为 {@code null}
         * @param owner       发起人
         * @param toolName    工具名
         * @param argsDigest  参数指纹
         * @param argsSummary 给人看的摘要
         * @param origin      来处，可以为 {@code null}
         * @param frozenPlan  冻结件，可以为 {@code null}
         */
        public Ask(String runId, String owner, String toolName, String argsDigest, String argsSummary,
                   Origin origin, String frozenPlan) {
            this(runId, owner, toolName, argsDigest, argsSummary, origin, frozenPlan, false);
        }

        /**
         * 既没有冻结件、也不接受长期放行的那一档。
         *
         * @param runId       哪一轮发起的，可以为 {@code null}
         * @param owner       发起人
         * @param toolName    工具名
         * @param argsDigest  参数指纹
         * @param argsSummary 给人看的摘要
         * @param origin      来处，可以为 {@code null}
         */
        public Ask(String runId, String owner, String toolName, String argsDigest, String argsSummary,
                   Origin origin) {
            this(runId, owner, toolName, argsDigest, argsSummary, origin, null, false);
        }
    }

    /**
     * 一条待批请求的来处。
     *
     * @param sessionKey 哪一段对话（含段）。批准之后用它开新一轮，不换段：上下文与 goal 都在
     *                   那段对话里
     * @param channel    卡片往哪个通道发。空表示这个通道没有卡片能力（HTTP 靠接口批）
     * @param chatRef    通道内的会话地址
     * @param principal  发起人。批准之后用他的身份调模型——用审批人的身份等于提权，而只存
     *                   {@code owner} 反推不出角色与可访问表
     */
    record Origin(String sessionKey, String channel, String chatRef, Principal principal) {
    }

    /**
     * 兑现一条批准：找到一条同人、同工具、同参数、仍然有效的批准，把它标成已用掉，
     * 然后把这条批准交回来。
     *
     * <p>做成"兑现"而不是先查后改，是因为一次批准只放行一次执行。查与改分成两次调用时，
     * 两个并发的同参数调用会同时查到同一条批准，于是同一次点头放行了两次副作用。兑现必须是一步
     * （实现里是一条 {@code update ... where consumed_at is null}）。
     *
     * <p>交回来的每一条都已经在有效期之内：过期判定由实现在 SQL 里用库的时钟比，不拿 JVM 时钟
     * 去比库里的时间戳——两者有偏差时一批批准会提前或延后作废，而那种事故没有日志可查。
     *
     * @param owner      发起人
     * @param toolName   要执行的工具
     * @param argsDigest 参数指纹
     * @return 可兑现的批准；空表示没有，调用方按"没有批准"处理
     */
    Optional<Grant> redeem(String owner, String toolName, String argsDigest);

    /**
     * 登记一条待批请求。
     *
     * <p>写不进去时返回空，调用方照样要拦下这次调用（没批过就是没批过），只是这一轮没有审批
     * 编号可回给用户。宁可让人多问一句"批了吗"，也不要因为记不下来就放行。
     *
     * @param ask 要登记的请求
     * @return 审批编号；写不进库时为空
     */
    Optional<String> ask(Ask ask);
}
