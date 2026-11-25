package io.github.claw4j.agent.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.SessionService;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Objects;

/**
 * 过期会话清理：只负责"什么时候跑"，判据在框架那一侧。
 *
 * <p>2026-11-25 之前这里有一套自己的判据（{@code SessionMaintenance}：看"最后一次事件"而不是
 * {@code expires_at}）。那一层随会话身份层一起下线，现在调
 * {@link SessionService#deleteExpiredSessions(Instant)}，删的是 {@code expires_at} 已过的行。
 *
 * <p>代价写在明处：{@code expires_at} 在建行时写死、不随活动刷新，所以一条天天在用的会话
 * 也会在第 N 天被删（ADR-0026 修订、ADR-0029 修订都记了这条）。要修得让框架在写事件时刷新它，
 * 而 {@code JdbcSessionRepository} 没有 UPDATE，本仓库做不了。
 *
 * <p>间隔用 {@code @Scheduled} 的属性占位符而不是从 properties 对象读：调度注解在容器启动时
 * 就要拿到值，那时绑定不一定完成；占位符走 Environment，与配置同一个来源。
 */
public class SessionExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(SessionExpirySweep.class);

    private final SessionService sessions;

    /**
     * @param sessions 会话服务
     */
    public SessionExpirySweep(SessionService sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /**
     * 跑一次清理。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：固定频率在上一次还没跑完时会排队，
     * 库慢的时候能积出一串同时执行的清理任务。
     */
    @Scheduled(fixedDelayString = "${claw4j.advisor.session.cleanup-interval:PT1H}")
    public void sweep() {
        int removed = sessions.deleteExpiredSessions(Instant.now());
        if (removed > 0) {
            log.info("过期会话清理：删掉 {} 条", removed);
        }
    }
}
