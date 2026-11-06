package io.github.claw4j.agent.session;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 一段对话"现在叫什么"的落点（ADR-0026）。
 *
 * <p>只有一个动作：把"这条对话被访问了"这件事落到库里，回一个段。
 * <b>判定放在 SQL 里，不放在 Java 里</b>——判定要读旧行（上一次是什么时候动的、
 * 那一段是哪天开的），读出来再决定写什么，就是"读-判-写"三步，
 * 两条消息同时进门时各自拿到旧值、各自判成"该换"，同一次轮换被做两次。
 * 一条 {@code insert ... on conflict ... returning} 就没有那个窗口。
 *
 * <p>接口在这里、实现只有 JDBC 一个：内存实现只出现在测试里，而它是为了让
 * "同日相等 / 空闲换段 / 跨天换段"这三件判定能被单独验证——那三件的输入是时间，
 * 不是数据库。
 */
public interface ConversationPointerStore {

    /**
     * 哪一段对话。三列合起来是主键，也是 {@code SessionKeys} 拼 id 用的那三样。
     *
     * @param owner   归属键（{@code userId@权限指纹}），不是裸 userId
     * @param channel 通道，缺省已由调用方落成 {@code '-'}
     * @param chatId  这段对话在通道里的地址，缺省同上
     */
    record Key(String owner, String channel, String chatId) {
    }

    /**
     * 这一次访问带来的两个决定 + 两个判据。
     *
     * <p>参数都是标量但语义差别很大，所以包一层：{@code now} 与 {@code idleBefore} 是
     * 两个相邻的 {@code Instant}，直接按位置传的话，写反了不会有任何编译错误，
     * 表现只是"空闲判定永远不触发"或"每句话都换段"——都是那种能安静跑很久的错。
     *
     * @param freshSegment 假如判定为"该换段"，新段叫什么（{@code <epochMilli>-<4 位随机>}）
     * @param now          这一刻，写进 {@code touched_at}
     * @param idleBefore   早于它的 {@code touched_at} 视为空闲超时
     * @param today        今天的逻辑日，写进 {@code day_started}（永远非空：那一列是 not null，
     *                     它记的是"这一段是哪天开的"，与判不判日历无关）
     * @param dailyGate    日历参不参与判定。{@code mode: idle} 时是 false，
     *                     实现据此走"只看空闲"的那条语句——不用 null 参数去做开关，
     *                     那要靠三值逻辑与驱动的类型推断同时成立，错一个就报
     *                     "could not determine data type of parameter"
     * @param forced       这一次必须换段，不看时间（用户发了 {@code /new}）。
     *                     <b>它是字段而不是"把 {@code idleBefore} 拨到未来"</b>：后者更省一行，
     *                     但会让 SQL 里那个参数明明叫 idleBefore、实际含义是"用户按了按钮"——
     *                     正是这个 record 存在的理由（写反了不会有任何编译错误）
     */
    record Rotation(String freshSegment, Instant now, Instant idleBefore, LocalDate today,
                    boolean dailyGate, boolean forced) {
    }

    /**
     * 记一次访问并回段。
     *
     * @return 空表示读不到（表没迁、库连不上）。调用方据此退回加段之前的形状，
     *         不把这一轮打死——段是"这段对话叫什么"，不是"能不能回答"
     */
    Optional<String> segmentOf(Key key, Rotation rotation);
}
