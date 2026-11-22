package io.github.claw4j.agent.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计开不开（ADR-0027 决定二）。
 *
 * <p>只有这一个开关，没有「留多久」：30 天与 10 万行是照 OpenClaw 的固定上限
 * （{@code docs/gateway/protocol/ledgers.md:17}），不是部署参数。做成可配的那一刻，「留存有上限」
 * 就变成一句取决于某个 yaml 的话，而下一个改配置的人不会知道它原来是有依据的。
 *
 * <p>默认 true，与 OpenClaw 的 {@code logging.audit.enabled} 同默认值
 * （{@code docs/gateway/config-observability.md:41}），理由也一样：只在出事之后才打开的审计，
 * 解释不了那次事故。
 *
 * <p>关掉之后：不再写新事件，已有记录照常可查、照常按 30 天与 10 万行清。OpenClaw 的原话是
 * 「Setting false stops new event collection immediately; existing records stay readable until
 * they expire.」
 */
@ConfigurationProperties(prefix = "claw4j.audit")
public class AuditProperties {

    /** 记不记新的审计事件，默认 true。 */
    private boolean enabled = true;

    /**
     * 记不记消息审计（{@code kind=message}），默认 <b>false</b>。
     *
     * <p>与上面那个开关默认值不同，是照 OpenClaw 的分法：运行与工具事件是运维账，
     * 默认就记；消息事件多出「谁跟谁说了话」这种关系数据，默认关，要查投递问题的人自己打开
     * （{@code docs/gateway/protocol/ledgers.md:117-118}）。
     *
     * <p>关掉只影响新行：已有的消息行照常可查、照常按保留期清。
     */
    private boolean messages = false;

    /**
     * 记不记新的审计事件，默认 true。
     *
     * @return 为真时写新事件
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 开关采集。关掉只停止新事件，已有记录与清理都不受影响。
     *
     * @param enabled 是否采集
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 记不记消息审计，默认 false。
     *
     * @return 为真时写消息行
     */
    public boolean isMessages() {
        return messages;
    }

    /**
     * 开关消息审计。
     *
     * @param messages 是否写消息行
     */
    public void setMessages(boolean messages) {
        this.messages = messages;
    }
}
