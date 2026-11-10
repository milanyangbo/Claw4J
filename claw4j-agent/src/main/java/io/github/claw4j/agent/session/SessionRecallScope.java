package io.github.claw4j.agent.session;

import io.github.claw4j.domain.principal.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 召回的范围解析（票 05）：这个身份能不能问这一段会话，以及去哪查。
 *
 * <p>两件事收在一处：散在工具里的话，加第五个档位时会出现"查询改了、归属校验没改"这种半改，
 * 表现是范围放开了但查不到，或者范围没放开却查到了。
 *
 * <p>两条查询路径，四档按 {@link SessionVisibility#crossSession()} 归并：
 * <ul>
 *   <li>{@code self} / {@code tree} 走框架的 {@code getEvents(sessionId, filter)}，范围钉死一段；
 *   <li>{@code agent} / {@code all} 走 {@link CrossSessionEvents}，一趟查完
 *       （为什么不能用 {@code findEventsByUserId} 顶替，见那个接口的注释）。
 * </ul>
 *
 * <p>归属校验按范围分：{@code self} / {@code tree} 核 {@code sessionId} 的前缀，防宿主把别人的
 * id 递进来；{@code agent} / {@code all} 不核——那两档本来就允许跨用户，再核前缀等于把范围
 * 悄悄收回 {@code self}。不核不等于不设防：身份为空仍然拒绝，动作仍然要过授权表
 * （{@code recall_session}）。
 */
public final class SessionRecallScope {

    private static final Logger log = LoggerFactory.getLogger(SessionRecallScope.class);

    private final SessionService sessions;

    private final CrossSessionEvents across;

    private final SessionVisibility visibility;

    /** 存储不可用只 WARN 一次：召回是每轮都可能触发的，刷屏会把别的日志挤掉。 */
    private final AtomicBoolean degraded = new AtomicBoolean();

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

    /** 一次判定的结果。三种拒绝要分开，因为它们对模型与对排查的人是三件事。 */
    public enum Admission {

        /** 可以查。 */
        ADMITTED,

        /** 没有身份：分不出该不该给。 */
        NO_PRINCIPAL,

        /** 没有会话标识：不知道该找哪一段。 */
        NO_SESSION,

        /** 这一段会话不属于这个身份（只在 {@code self} / {@code tree} 下可能出现）。 */
        NOT_YOURS
    }

    /**
     * 这个身份能不能问这一段会话。
     *
     * <p>三种拒绝用枚举而不是 boolean：{@code NO_PRINCIPAL} 与 {@code NOT_YOURS} 在日志上都只是
     * "拒了"，对模型却是两句不同的话——一句让它补上下文，一句说明这是别人的会话。合成一个
     * false 之后，工具只能回一句含糊的"不能查"，排查时也分不清是宿主漏传还是越权尝试。
     *
     * @param who       当前身份，可以为 null（单参入口就没有）
     * @param sessionId 当前这一段，可以为 null
     * @return 判定结果，{@link Admission#ADMITTED} 表示可以查
     */
    public Admission admit(Principal who, String sessionId) {
        if (who == null || who.userId() == null || who.userId().isBlank()) {
            return Admission.NO_PRINCIPAL;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return Admission.NO_SESSION;
        }
        if (visibility.checksOwnership() && !belongsTo(who, sessionId)) {
            return Admission.NOT_YOURS;
        }
        return Admission.ADMITTED;
    }

    /**
     * 这一段会话是不是这个身份的。
     *
     * <p>会话 id 的形状是 {@code claw4j:user:<owner>:<通道>:<chatId>:<段>}，{@code owner} 里带着
     * 权限指纹（{@link SessionKeys#ownerOf}）。前缀对不上，说明换人了、换权限了、
     * 或者宿主把别人的 id 递了进来——三种都不该查。
     *
     * <p>前缀只到 {@code <owner>:} 为止，所以其后接什么（通道、chatId、段）都成立。反过来，
     * 把新维度拼在 owner 前面会让这条校验对别人成立——那是一条静默的越权读。
     *
     * @param who       当前身份，为 null 时返回 false
     * @param sessionId 待校验的会话 id，为 null 时返回 false
     * @return 前缀匹配则为 true
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
     * <p>存储不可用时两条路都退化成"没有命中"：{@code self} 那一路由
     * {@link FaultTolerantSessionService} 兜着，跨会话这一路在这里自己兜。不兜的话，同一个库
     * 挂掉，配 {@code self} 的部署答"没找到"、配 {@code all} 的部署整轮失败——档位不同不该是
     * "能不能回答"的差别。
     *
     * <p>降级不静默：不 WARN 的话，"库挂了"与"这段历史确实没说过"在日志上完全一样，
     * 而两者的处置差得很远——一个修库，一个换关键词。
     *
     * @param sessionId 当前这一段；跨会话档不用它，但仍然要传——它是"你在哪个上下文里问的"，
     *                  空的话连该不该查都判断不了
     * @param filter    过滤条件
     * @return 命中的事件；降级或没命中时是空列表
     */
    public List<SessionEvent> events(String sessionId, EventFilter filter) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(filter, "filter");
        if (visibility.crossSession()) {
            try {
                return across.find(filter);
            }
            catch (DataAccessException storageDown) {
                if (degraded.compareAndSet(false, true)) {
                    log.warn("跨会话召回查不了（visibility={}），这一轮按没有命中处理；"
                                    + "后续同样的失败不再逐条打印：{}",
                            visibility.key(), storageDown.getMessage());
                }
                return List.of();
            }
        }
        return sessions.getEvents(sessionId, filter);
    }
}
