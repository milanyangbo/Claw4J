package io.github.claw4j.domain.port;

import io.github.claw4j.domain.principal.Principal;

import java.time.Instant;
import java.util.Optional;

/**
 * 审批账本：这个人此刻针对<b>这一次具体要干的事</b>有没有一条仍然有效的批准（P01、P02）。
 *
 * <p><b>为什么它回答"有没有"，不回答"批不批"</b>：批是一次人的决定，发生在这条调用链之外
 * （HTTP 接口、飞书卡片，P03）。鉴权这一端只可能在工具执行之前问一句"有人批过吗"，
 * 所以端口收窄到这两件事——问一句、记一条，把"等人点、超时作废"留给
 * {@link ApprovalDecider}，鉴权 Advisor 就仍然只是一个做判定的组件。
 *
 * <p><b>口径从"工具"细化到"工具 + 参数"</b>（P02）。P01 只按工具名问，于是
 * "批了查一次上周成交额"也放行了"查全表"。加进<b>参数指纹</b>之后，
 * 批准的对象是"这一次具体会跑起来的那个调用"——参数变了就是另一次，要重新批。
 *
 * <p>指纹的形状是 {@code sha256(工具名 + 规范化参数)}，<b>不含 runId 也不含序号</b>。
 * 这与幂等键（ADR-0017）刚好相反：那个要区分每一次执行，这个要跨执行复用一次批准。
 * 两个哈希形状相似、口径相反，混用的事故是双向的——用幂等键当批准键，
 * 每换一件事都要重新批一遍；用批准键当幂等键，同一件事里第二次同参数调用会被当成
 * "已经做过"而跳过。
 *
 * <p><b>没有批准是一条正常答案，不是错误</b>：查不到就是"没有"，调用方照着拒绝走。
 * 这也决定了它读失败时的姿势——见 {@code JdbcApprovalLedger}：读不到按"没有"处理，
 * 宁可拦住一个本来批过的动作，也不放过一个没人批过的。
 *
 * <p>实现在 Agent 模块（审批表与 run 在同一个 schema 下），装配在
 * {@code AgentPersistenceConfiguration}；没有装配时鉴权那一层退到
 * {@code ApprovalGate#denyAll()}——"没有审批设施"的失败姿势是拦住，不是放行。
 *
 * <p><b>这里不提供 {@code empty()} 那种空实现</b>：{@code domain.port} 只放契约
 * （{@code DomainBoundaryTest} 会红），而"没有审批时怎么办"是装配侧的决定，
 * 归 {@code ApprovalGate}。
 */
public interface ApprovalLedger {

    /**
     * 一条仍然有效的批准。
     *
     * @param id        审批记录的 id。拒绝话术与审计都要能指回具体那一条
     * @param expiresAt 批到什么时候。过期与否由实现判定（{@code expires_at > now()}），
     *                  调用方拿到的每一条都已经是在有效期内的
     */
    record Grant(String id, Instant expiresAt) {
    }

    /**
     * 一次"我想干这件事，但没人批过"的登记。
     *
     * @param runId       这条请求属于哪件事。可以为 null（子 Agent 那一层还没有 runId，A10），
     *                    为空时不挂起任何 run——没有 run 可挂
     * @param owner       发起这次调用的人（{@link io.github.claw4j.domain.principal.Principal#userId()}）。
     *                    批准是<b>批给某个人的</b>，不是批给某个动作：同一句话换个没被批过的人来问，
     *                    仍然要重新批一遍
     * @param toolName    要执行的工具，不是动作名——动作是权限的口径（{@code send_email}），
     *                    批准的对象则是"这一次具体会跑起来的那个工具"
     * @param argsDigest  参数指纹（{@link #redeem} 的同一个口径）。它把批准从"这个工具"
     *                    收窄到"这个工具的这一种调用方式"
     * @param argsSummary 给人看的参数摘要（已脱敏、已截断）。审批人看不到自己批的是什么时，
     *                    点下去的那个按钮等于盖章
     * @param origin      这条请求从哪段对话来的（ADR-0027）。批准之后就在那段对话里开新一轮，
     *                    而不用拿 {@code runId} 去唤醒一件"挂着的事"。
     *                    为 null 表示这一层还没有对话（子 Agent，A10）——那时结论落得下来，
     *                    但没有"接着做"这回事
     */
    record Ask(String turnId, String owner, String toolName, String argsDigest, String argsSummary,
               Origin origin) {
    }

    /**
     * 一条待批请求的来处。
     *
     * @param sessionKey 哪一段对话（含段）。批准之后用它开新一轮，<b>不换段</b>：
     *                   上下文与 goal 都在那段对话里
     * @param channel    卡片往哪个通道发。空表示这个通道没有卡片能力（HTTP 靠接口批）
     * @param chatRef    通道内的会话地址
     * @param principal  发起人。批准之后用他的身份调模型——用审批人的身份等于提权，
     *                   而只存 {@code owner} 反推不出角色与可访问表
     */
    record Origin(String sessionKey, String channel, String chatRef, Principal principal) {
    }

    /**
     * 兑现一条批准：找到一条同人、同工具、<b>同参数</b>、仍然有效的批准，
     * 把它标成已用掉，然后把这条批准交回来。
     *
     * <p><b>为什么是"兑现"而不是先查后改</b>：一次批准只放行一次执行。
     * 查与改分成两次调用时，两个并发的同参数调用会同时查到同一条批准，
     * 于是同一次点头放行了两次副作用。兑现必须是一步（实现里是一条
     * {@code update ... where consumed_at is null}）。
     *
     * <p>交回来的每一条都已经在有效期之内：过期判定由实现在 SQL 里用库的时钟比，
     * 不拿 JVM 时钟去比库里的时间戳——两者有偏差时，一批批准会提前或延后作废，
     * 而那种事故没有日志可查。
     *
     * @return 空表示没有可兑现的批准。调用方按"没有批准"处理
     */
    Optional<Grant> redeem(String owner, String toolName, String argsDigest);

    /**
     * 登记一条待批请求。
     *
     * <p>写不进去时返回空——调用方照样要拦下这次调用（没批过就是没批过），
     * 只是这一轮没有审批编号可回给用户。宁可让人多问一句"批了吗"，
     * 也不要因为记不下来就放行。
     *
     * @return 审批编号
     */
    Optional<String> ask(Ask ask);
}
