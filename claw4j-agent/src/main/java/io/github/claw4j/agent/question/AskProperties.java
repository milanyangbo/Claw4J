package io.github.claw4j.agent.question;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 交互提问的配置，键前缀 {@code claw4j.tools.ask}。
 *
 * <p>超时照 OpenClaw 的 {@code ask_user}：默认 900 秒，钳在 30 到 3600 之间
 * （{@code docs/tools/ask-user.md}「The default timeout is 900 seconds. {@code timeoutSeconds} is
 * clamped to the range 30 through 3600 seconds.」）。钳在两端而不是照单全收：
 * 30 秒以下人来不及看，3600 秒以上那一轮已经没人记得在等什么了。
 *
 * <p>这个值是一轮里**最多等多个人**的时间，不是"模型这次调用的总预算"。与 OpenClaw 同一条：
 * 等待不暂停也不延长调用本身的超时。
 *
 * @param enabled 装不装这个工具；默认装。关掉之后模型没有「问一句再继续」这条路径
 * @param timeoutSeconds 一次最多等人多久；空按 900
 */
@ConfigurationProperties("claw4j.tools.ask")
public record AskProperties(Boolean enabled, Integer timeoutSeconds) {

    /** 默认等人时长。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 900;

    /** 下限：比这更短，人还没看到问题就过期了。 */
    public static final int MIN_TIMEOUT_SECONDS = 30;

    /** 上限：比这更长，那一轮已经没人记得在等什么。 */
    public static final int MAX_TIMEOUT_SECONDS = 3600;

    public AskProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        timeoutSeconds = clamp(timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds);
    }

    /**
     * 这一层装不装。
     *
     * @return 没配时为 {@code true}
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 等人时长。
     *
     * @return 钳过之后的时长
     */
    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    private static int clamp(int seconds) {
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, seconds));
    }
}
