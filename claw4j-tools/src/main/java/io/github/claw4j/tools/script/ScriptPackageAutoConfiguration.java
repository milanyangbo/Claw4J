package io.github.claw4j.tools.script;

import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * 脚本层的装配：配了 {@code claw4j.scripts.directory} 就有这一层，没配就没有。
 *
 * <p><b>默认不启用</b>。脚本层是三层里唯一能把"执行"这件事放开的一层，
 * 它的存在应该是一个决定，而不是"引了依赖就自动有"。
 * 于是开关是"有没有配目录"，而不是"classpath 上有没有这个类"。
 *
 * <p><b>权限登记表不用 {@code @ConditionalOnMissingBean(ToolPermissionRegistry.class)}</b>：
 * 那条条件的语义是"容器里已经有了就让位"，于是三个来源各登记各的时，
 * 后处理的那一个会静默不注册——它的工具从此一律按"未登记"被拒。
 * 这是 01 号票修掉、02 号票又在 MCP 上重演一遍的同一个形状。
 * 鉴权 Advisor 消费的是一张表<b>的列表</b>，多个登记表本来就是设计上允许的。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "claw4j.scripts", name = "directory")
public class ScriptPackageAutoConfiguration {

    /**
     * 包目录里的每个子目录都是一个包。目录不存在即启动失败
     * （见 {@link ScriptPackageCatalog#load}）：配了却不存在是配置错误，
     * 不是"这一层不存在"。
     */
    @Bean
    public ScriptPackageToolProvider scriptPackageToolProvider(
            @Value("${claw4j.scripts.directory}") String directory) {
        return new ScriptPackageToolProvider(ScriptPackageCatalog.load(Path.of(directory)));
    }

    /** 这一层的权限登记表：动作是跑脚本，业务表一张都没有（ADR-0003）。 */
    @Bean
    public ToolPermissionRegistry scriptPackagePermissionRegistry(ScriptPackageToolProvider provider) {
        return ToolPermissionRegistry.of(provider.permissions());
    }
}
