package io.github.claw4j.capability.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 沙箱这一层的装配：配了 {@code mode} 就探一次 docker，探不通就别起来。
 *
 * <p>探测放在启动期而不是第一次执行时，理由是失败要早、要一次说清：第一次执行才发现，
 * 那条命令已经"跑过了"（模型以为它跑过），而且错在一条工具结果里，看起来像脚本的问题。
 *
 * <p>{@code mode=off} 时一个 bean 都不产出，也不探——默认零变化。
 *
 * <p>这里故意<b>不</b>提供"探测失败就回落到宿主"的分支：配了 docker 却没探通，说明部署与配置
 * 对不上，这时候最不该做的事是继续跑。
 */
@AutoConfiguration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SandboxAutoConfiguration.class);

    /**
     * 指向真实 docker 的 CLI，启动期完成两条探测。
     *
     * <p>两条探测都失败时只报第一条：守护进程都没起来的时候，"镜像不在"是它的后果，
     * 一起报只会让人以为要拉两个镜像。
     *
     * @param properties {@code claw4j.tools.sandbox} 的配置
     * @return 探过的 CLI
     * @throws SandboxUnavailableException 配置做不到，或探测不通
     */
    @Bean
    @Conditional(SandboxEnabled.class)
    public DockerCli dockerCli(SandboxProperties properties) {
        properties.requireUsable();
        DockerCli cli = DockerCli.resolve(properties);
        cli.requireUsable(properties.image());
        log.info("沙箱已开：mode={}，backend={}，镜像={}，工作目录挂载={}——命令跑在容器里，不再跑在宿主进程里",
                properties.mode(), properties.backend(), properties.image(), properties.workspaceAccess());
        return cli;
    }

    /** {@code mode} 不是 {@code off}（含未配置）时装配。写错的值交给绑定去报错，这里不当成 off。 */
    static final class SandboxEnabled implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String mode = context.getEnvironment().getProperty("claw4j.tools.sandbox.mode", "off");
            return mode != null && !"off".equalsIgnoreCase(mode.strip());
        }
    }
}
