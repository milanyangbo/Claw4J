package io.github.claw4j.agent.session;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;

import java.util.List;

/**
 * 跨会话的事件检索：一趟查完整库，不按会话收窄（票 05）。
 *
 * <p><b>为什么需要它</b>：框架只给了两个入口——{@code getEvents(sessionId, filter)} 与
 * {@code findEventsByUserId(userId, filter)}（{@code spring-ai-session:0.10.0}，{@code javap} 实看）。
 * 前者钉死一段会话，后者钉死一个 {@code user_id}，而我们的 {@code user_id} 是
 * {@code userId@权限指纹}（{@link SessionKeys#ownerOf}）——用它顶替"全部会话"会把
 * {@code visibility=all} 静默退化成"这个人的这份权限"。
 *
 * <p><b>为什么不做成通用实现</b>：本方法只认召回工具真正会拼的那几样
 * （复数 {@code keywords} / {@code matchMode} / {@code from} / {@code to} /
 * {@code excludeSynthetic} / {@code excludeArchived} / {@code messageTypes} /
 * {@code page} / {@code pageSize}）。{@code pattern}、{@code lastN} 与单数 {@code keyword}
 * 在 SQL 里是另一套语义（方言正则、倒序窗口、单值条件），而召回不用它们——
 * 设了就直接报不支持，而不是悄悄忽略：忽略的表现是"结果少了"或"结果多了"，
 * 而两者与"历史里本来就说过 / 没说过"在界面上完全一样。
 */
public interface CrossSessionEvents {

    /**
     * 按过滤条件在全部会话里找，顺序与框架的 {@code getEvents} 一致（{@code seq} 升序，
     * 再按 {@code page}/{@code pageSize} 前向切片）。
     *
     * @param filter 过滤与分页
     * @throws UnsupportedOperationException 带了本接口不支持的条件（{@code pattern} /
     *                                       {@code lastN} / 单数 {@code keyword}）时
     */
    List<SessionEvent> find(EventFilter filter);
}
