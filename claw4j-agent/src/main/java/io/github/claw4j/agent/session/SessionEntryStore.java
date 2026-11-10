package io.github.claw4j.agent.session;

import java.time.Instant;
import java.util.Optional;

/**
 * 会话节点与段的状态（ADR-0026、ADR-0029）。
 *
 * <p>只有一个动作：把"这条对话被访问了"落下去，回一个段。落的是 V8 的两张表——
 * {@code session_nodes}（节点，热事实在 {@code entry_json}）与 {@code session_windows}
 * （段，一词一代）。换段 = 插一行 window + 改 {@code current_session_id}，两件事在同一个
 * 事务里，不会出现"段换了、window 没插"的中间态。
 *
 * <p>判定要读旧行（上一段什么时候开始、最后一次交互是什么时候），读出来再决定写什么就是
 * "读-判-写"三步，两条消息同时进门时各自拿到旧值、各自判成"该换"，同一次轮换被做两次。
 * 实现用 {@code select ... for update} 把那一行锁住，判定在锁内做。
 *
 * <p>实现只有 JDBC 一个，内存实现只出现在测试里：它让"同日相等 / 空闲换段 / 跨天换段"
 * 三件判定能被单独验证——那三件的输入是时间，不是数据库。
 */
public interface SessionEntryStore {

    /**
     * 一行节点的定位键，外加写 window 需要的几个事实。
     *
     * @param sessionKey 不含段的会话键（{@link SessionKeys#conversationKeyOf}），主键
     * @param owner      归属键（{@code userId@权限指纹}），不是裸 userId
     * @param channel    通道，缺省已由调用方落成 {@code '-'}
     * @param chatId     这段对话在通道里的地址，缺省同上
     * @param direct     是不是私聊。决定 window 上的 {@code chat_type} 与 {@code session_scope}：
     *                   私聊且键落到 {@code main} 的是 {@code shared-main}，其余是
     *                   {@code conversation}，群聊是 {@code group}
     */
    record Key(String sessionKey, String owner, String channel, String chatId, boolean direct) {
    }

    /**
     * 这一次访问带来的两个决定与两个判据。
     *
     * <p>参数都是标量但语义差别很大，所以包一层：{@code now} 与 {@code idleBefore} 是两个相邻的
     * {@code Instant}，按位置直接传的话写反了不会有任何编译错误，表现只是"空闲判定永远不触发"
     * 或"每句话都换段"。
     *
     * @param freshSegment 假如判定为"该换段"，新段叫什么（{@code <epochMilli>-<4 位随机>}）
     * @param now          这一刻，写进 {@code last_interaction_at} 与 {@code session_started_at}
     * @param idleBefore   早于它的 {@code last_interaction_at} 视为空闲超时
     * @param dailyResetAt 最近一个 at-hour 边界，是时刻而不是逻辑日。
     *                     {@code session_started_at} 早于它即跨天；存时刻的话，
     *                     改 at-hour 或改时区之后判定立刻跟着变
     * @param dailyGate    日历参不参与判定。{@code mode: idle} 时是 false，实现据此走
     *                     "只看空闲"的那条语句——不用 null 参数做开关，那要靠三值逻辑与驱动的
     *                     类型推断同时成立，错一个就报"could not determine data type of parameter"
     * @param forced       这一次必须换段，不看时间（用户发了 {@code /new}）。做成独立字段而不是
     *                     把 {@code idleBefore} 拨到未来：后者更省一行，但会让 SQL 里那个参数
     *                     名叫 idleBefore、实际含义是"用户按了按钮"
     */
    record Rotation(String freshSegment, Instant now, Instant idleBefore, Instant dailyResetAt,
                    boolean dailyGate, boolean forced) {
    }

    /**
     * 记一次访问并回段。换段时插一行 window、改 {@code current_session_id}，
     * 并把 {@code entry_json} 里的目标与用量清掉。
     *
     * <p>目标是这一段对话的承诺，不是历史：换段之后那件事已经不用再推，而旧段的记忆还在。
     * 清在同一个事务里，所以"段换了、目标还挂着"这个中间态不存在。
     *
     * @param key      这一行的定位键
     * @param rotation 这一次访问的决定与判据
     * @return 空表示读不到（表没迁、库连不上）。调用方据此退回不加段之前的形状，
     *         不把这一轮打死——段是"这段对话叫什么"，不是"能不能回答"
     */
    Optional<String> segmentOf(Key key, Rotation rotation);
}
