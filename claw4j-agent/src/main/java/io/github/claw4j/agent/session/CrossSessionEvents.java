package io.github.claw4j.agent.session;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;

import java.util.List;

/**
 * 跨全部会话检索事件，一趟查完整库，不按会话收窄（票 05）。
 *
 * <p>框架只有两个带 filter 的查询入口：{@code getEvents(sessionId, filter)} 与
 * {@code findEventsByUserId(userId, filter)}。前者钉死一段会话，后者钉死一个 {@code user_id}，
 * 而本仓库的 {@code user_id} 是 {@code userId@权限指纹}（{@link SessionKeys#ownerOf}）——
 * 拿它顶替全部会话，会让 {@code visibility=all} 静默退化成"这个人的这份权限"。
 *
 * <p>只认召回工具会拼的条件：复数 {@code keywords}、{@code matchMode}、{@code from}、
 * {@code to}、{@code excludeSynthetic}、{@code excludeArchived}、{@code messageTypes}、
 * {@code page}、{@code pageSize}。{@code pattern}、{@code lastN} 与单数 {@code keyword}
 * 在 SQL 里是另一套语义（方言正则、倒序窗口、单值条件），实现遇到就抛
 * {@link UnsupportedOperationException}：静默忽略的表现是"结果多了"，
 * 与"历史里本来就说过"在界面上分不开。
 */
public interface CrossSessionEvents {

    /**
     * 按过滤条件在全部会话里查找，顺序与框架的 {@code getEvents} 一致：{@code seq} 升序，
     * 再按 {@code page} / {@code pageSize} 前向切片。
     *
     * @param filter 过滤与分页条件
     * @return 这一页的事件，没有命中时是空列表
     * @throws UnsupportedOperationException 带了 {@code pattern}、{@code lastN} 或单数
     *                                       {@code keyword} 时
     */
    List<SessionEvent> find(EventFilter filter);
}
