package io.github.claw4j.tools.script;

import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
 *
 * <p><b>清单（registry）与执行（runner）分开成两个 bean</b>（06 号票）：
 * 人审通过的包是在运行中被加进清单的，而加进清单必须同时把它的执行后端建好。
 * 两个 bean 之后，生成器那一层只要拿到 registry 就能完成这件事，
 * 不必知道执行后端是怎么建的。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "claw4j.scripts", name = "directory")
public class ScriptPackageAutoConfiguration {

    /**
     * 包清单：启动时从目录读进来，之后只接受人审通过的包（06 号票）。
     *
     * <p>目录不存在即启动失败（见 {@link ScriptPackageCatalog#load}）：
     * 配了却不存在是配置错误，不是"这一层不存在"。
     */
    @Bean
    public ScriptPackageRegistry scriptPackageRegistry(
            @Value("${claw4j.scripts.directory}") String directory) {
        return new ScriptPackageRegistry(ScriptPackageCatalog.load(Path.of(directory)));
    }

    /**
     * 执行入口：换沙箱的地方是 {@link ProcessRunnerFactory}，装配时不写死。
     *
     * <p><b>把"新包要建后端"这条挂上去的动作只做一次</b>，在
     * {@link ScriptPackageToolProvider} 的构造里。挂两次的话，
     * 注册一个包会触发两次后端注册，而第二次撞上"重名即失败"——
     * 报出来的是"已经有一个叫 X 的包"，离"listener 挂重了"隔着一层。
     */
    @Bean
    public ScriptPackageRunner scriptPackageRunner(ScriptPackageRegistry registry) {
        return new ScriptPackageRunner(registry.packages());
    }

    @Bean
    public ScriptPackageToolProvider scriptPackageToolProvider(ScriptPackageRegistry registry,
                                                               ScriptPackageRunner runner,
                                                               ExecutionListeners executionListeners) {
        return new ScriptPackageToolProvider(registry, runner, executionListeners);
    }

    /**
     * 执行观察者的容器：先落地、后来者往里加（见 {@link ExecutionListeners}）。
     *
     * <p>没有别的来源时它是空的，于是"没接审计"表现为空容器而不是装配失败——
     * 审计是留痕手段，不是这条链路能不能工作的前提。
     */
    @Bean
    @ConditionalOnMissingBean
    public ExecutionListeners executionListeners() {
        return new ExecutionListeners();
    }

    /** 这一层的权限登记表：动作是跑脚本，业务表一张都没有（ADR-0003）。 */
    @Bean
    public ToolPermissionRegistry scriptPackagePermissionRegistry(ScriptPackageToolProvider provider) {
        return ToolPermissionRegistry.of(provider.permissions());
    }
}
