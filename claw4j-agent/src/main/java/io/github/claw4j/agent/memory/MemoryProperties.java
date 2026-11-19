package io.github.claw4j.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 长期记忆的可调项。
 *
 * <p>{@code directory} 不给默认值：配了才有这一层（见 {@code MemoryAutoConfiguration}）。
 * 给一个默认目录等于默认打开它——记忆会落盘、会跨会话累积，这件事得在配置里说清楚过。
 *
 * @param directory 记忆根目录，每个人在它下面一个子目录
 * @param dream 要不要在后台整理记忆（库的 dream 周期）。关掉只是不整理，读写记忆照旧
 */
@ConfigurationProperties("claw4j.memory")
public record MemoryProperties(String directory, @DefaultValue("true") boolean dream) {

    public MemoryProperties {
        directory = directory == null ? "" : directory.trim();
    }
}
