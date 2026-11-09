package io.github.claw4j.agent.session;

import java.time.Instant;
import java.util.Optional;

/**
 * 会话节点的状态行（ADR-0026，票 06 起形状照 OpenClaw 的 {@code SessionEntry}）。
 *
 * <p>只有一个动作：把"这条对话被访问了"这件事落到 {@code session_entry} 上，回一个段。
 * 这一行同时装着 {@code sessionId}（段）、两个时间、累计用量与目标——
 * 与它的一条 entry 同形，所以"换段"与"清目标"是<b>同一条写</b>，不会出现
 * "段换了、目标还在"的中间态。
 *
 * <p><b>判定放在 SQL 里，不放在 Java 里</b>——判定要读旧行（上一段什么时候开始、
 * 最后一次交互是什么时候），读出来再决定写什么，就是"读-判-写"三步，
 * 两条消息同时进门时各自拿到旧值、各自判成"该换"，同一次轮换被做两次。
 * 一条 {@code insert ... on conflict ... returning} 就没有那个窗口。
 *
 * <p>接口在这里、实现只有 JDBC 一个：内存实现只出现在测试里，而它是为了让
 * "同日相等 / 空闲换段 / 跨天换段"这三件判定能被单独验证——那三件的输入是时间，
 * 不是数据库。
 */
public interface SessionEntryStore {

    /**
     * 一行 entry 的定位键。
     *
     * @param sessionKey 不含段的会话键（{@link SessionKeys#conversationKeyOf}），主键
     * @param owner      归属键（{@code userId@权限指纹}），不是裸 userId
     * @param channel    通道，缺省已由调用方落成 {@code '-'}
     * @param chatId     这段对话在通道里的地址，缺省同上
     */
    record Key(String sessionKey, String owner, String channel, String chatId) {
    }

    /**
     * 这一次访问带来的两个决定 + 两个判据。
     *
     * <p>参数都是标量但语义差别很大，所以包一层：{@code now} 与 {@code idleBefore} 是
     * 两个相邻的 {@code Instant}，直接按位置传的话，写反了不会有任何编译错误，
     * 表现只是"空闲判定永远不触发"或"每句话都换段"——都是那种能安静跑很久的错。
     *
     * @param freshSegment 假如判定为"该换段"，新段叫什么（{@code <epochMilli>-<4 位随机>}）
     * @param now          这一刻，写进 {@code last_interaction_at} 与 {@code session_started_at}
     * @param idleBefore   早于它的 {@code last_interaction_at} 视为空闲超时
     * @param dailyResetAt 最近一个 at-hour 边界（<b>时刻</b>，不是逻辑日）。
     *                     {@code session_started_at} 早于它 → 跨了天。
     *                     存结论还是存时刻，是这次与旧实现的差别：存时刻的话，
     *                     改 at-hour 或改时区之后判定立刻跟着变
     * @param dailyGate    日历参不参与判定。{@code mode: idle} 时是 false，
     *                     实现据此走"只看空闲"的那条语句——不用 null 参数去做开关，
     *                     那要靠三值逻辑与驱动的类型推断同时成立，错一个就报
     *                     "could not determine data type of parameter"
     * @param forced       这一次必须换段，不看时间（用户发了 {@code /new}）。
     *                     <b>它是字段而不是"把 {@code idleBefore} 拨到未来"</b>：后者更省一行，
     *                     但会让 SQL 里那个参数明明叫 idleBefore、实际含义是"用户按了按钮"——
     *                     正是这个 record 存在的理由（写反了不会有任何编译错误）
     */
    record Rotation(String freshSegment, Instant now, Instant idleBefore, Instant dailyResetAt,
                    boolean dailyGate, boolean forced) {
    }

    /**
     * 记一次访问并回段。换段时同一行里的 {@code goal_*} 列被清空、{@code total_tokens} 归零。
     *
     * @return 空表示读不到（表没迁、库连不上）。调用方据此退回加段之前的形状，
     *         不把这一轮打死——段是"这段对话叫什么"，不是"能不能回答"
     */
    Optional<String> segmentOf(Key key, Rotation rotation);
}
