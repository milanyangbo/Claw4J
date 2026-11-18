package io.github.claw4j.capability.sandbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 沙箱配置的绑定。
 *
 * <p>2026-11-18 之前这里还有一个"启动期探一次 docker"的 bean——那条探测服务的只有脚本层
 * （脚本包要拼 {@code docker run}）。脚本层退化成技能之后，容器只剩 {@code exec} 一侧，
 * 而那一侧由库的 {@code DockerCliExecBackend.build()} 自己 fail-fast（起不来就抛），
 * 不需要再多探一次。
 *
 * <p>{@code mode=off} 时这一层什么都不做，默认零变化。
 */
@AutoConfiguration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxAutoConfiguration {
}
