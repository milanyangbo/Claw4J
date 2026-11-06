package io.github.claw4j.agent.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计开不开（ADR-0027 决定二）。
 *
 * <p><b>只这一个开关，不再有"留多久"</b>：30 天与 10 万行是照 OpenClaw 的固定上限
 * （commit f533469），不是部署参数。做成可配的那一刻，"留存有上限"就变成一句
 * 取决于某个 yaml 的话，而下一个改配置的人不会知道它原来是有依据的。
 *
 * <p><b>默认 true</b>，与 OpenClaw 的 {@code logging.audit.enabled} 同默认值，
 * 理由也一样：只在出事之后才打开的审计，解释不了那次事故。
 *
 * <p>关掉之后：不再写新事件，已有记录照常可查、照常按 30 天与 10 万行清。
 * OpenClaw 的原话是 "Setting false stops new event collection immediately;
 * existing records stay readable until they expire."
 */
@ConfigurationProperties(prefix = "claw4j.audit")
public class AuditProperties {

    /** 记不记新的审计事件。默认 true。 */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
