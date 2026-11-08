package io.github.claw4j.agent.session;

import io.github.claw4j.domain.principal.Principal;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.Objects;

/**
 * 召回的范围解析，收在这一处（票 05）。
 *
 * <p><b>为什么要有这个类</b>：范围由两件事共同决定——{@code visibility} 的取值，
 * 以及"这个身份能不能碰这一段会话"。两件都散在工具里的话，加第五个档位时会出现
 * "查询改了、归属校验没改"这种半改：表现是范围放开了但查不到，或者范围没放开却查到了。
 *
 * <p><b>四个分支</b>（与 {@link SessionVisibility} 的四档一一对应）：
 * <ul>
 *   <li>{@code self} / {@code tree} → 框架的 {@code getEvents(sessionId, filter)}，范围钉死一段；</li>
 *   <li>{@code agent} / {@code all} → {@link CrossSessionEvents}，一趟查完（见那个接口的注释：
 *       为什么不能用 {@code findEventsByUserId} 顶替）。</li>
 * </ul>
 *
 * <p><b>归属校验按范围分，这是本类最需要被 review 的一段</b>：
 * {@code self} / {@code tree} 核 {@code sessionId} 的前缀（防宿主把别人的 id 递进来）；
 * {@code agent} / {@code all} <b>不核</b>——那两档本来就允许跨用户，
 * 再核前缀等于把范围又悄悄收回 {@code self}，而症状只是"配了 all 却查不到别人的"。
 * 不核不等于不设防：身份为空仍然拒绝，动作仍然要过授权表（{@code recall_session}）。
 */
public final class SessionRecallScope {

    private final SessionService sessions;

    private final CrossSessionEvents across;

    private final SessionVisibility visibility;

    public SessionRecallScope(SessionService sessions, CrossSessionEvents across,
                              SessionVisibility visibility) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.across = Objects.requireNonNull(across, "across");
        this.visibility = visibility == null ? SessionVisibility.DEFAULT : visibility;
    }

    /** 生效范围。工具描述与"没找到"的文案都要按它拼。 */
    public SessionVisibility visibility() {
        return visibility;
    }

    /**
     * 这一段会话是不是这个身份的。
     *
     * <p>会话 id 的形状是 {@code claw4j:user:<owner>:<通道>:<chatId>:<段>}，{@code owner} 里带着
     * 权限指纹（{@link SessionKeys#ownerOf}）。前缀对不上，说明换人了、换权限了、
     * 或者宿主把别人的 id 递了进来——三种都不该查。
     *
     * <p>前缀只到 {@code <owner>:} 为止，所以后面接什么（通道、chatId、段）都成立。
     * 反过来，把新维度拼在 owner 前面会让这条校验对别人成立——那是一条静默的越权读。
     */
    public static boolean belongsTo(Principal who, String sessionId) {
        if (who == null || sessionId == null) {
            return false;
        }
        String prefix = SessionKeys.PREFIX + SessionKeys.ownerOf(who) + SessionKeys.SESSION_SEPARATOR;
        return sessionId.startsWith(prefix);
    }

    /**
     * 查一次。
     *
     * <p><b>存储不可用时两条路都退化成"没有命中"</b>：{@code self} 那一路由
     * {@link FaultTolerantSessionService} 兜着，跨会话这一路在这里自己兜。
     * 不兜的话，同一个库挂掉，配 {@code self} 的部署答"没找到"、配 {@code all} 的部署整轮 500
     * ——档位不同不该是"能不能回答"的差别。
     *
     * @param sessionId 当前这一段；跨会话档不用它，但仍然要传——它是"你在哪个上下文里问的"
     *                  ，空的话连该不该查都判断不了
     */
    public List<SessionEvent> events(String sessionId, EventFilter filter) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(filter, "filter");
        if (visibility.crossSession()) {
            try {
                return across.find(filter);
            }
            catch (DataAccessException storageDown) {
                return List.of();
            }
        }
        return sessions.getEvents(sessionId, filter);
    }
}
