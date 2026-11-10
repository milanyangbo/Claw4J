package io.github.claw4j.capability.script;

import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * 脚本层的装配：配了 {@code claw4j.scripts.directory} 就有这一层，没配就没有。
 *
 * <p>默认不启用。脚本层是三层里唯一能把"执行"这件事放开的一层，它的存在应该是一个决定，
 * 而不是"引了依赖就自动有"，于是开关是"有没有配目录"。
 *
 * <p>权限登记表不能用 {@code @ConditionalOnMissingBean(ToolPermissionRegistry.class)}：
 * 那条条件的语义是"容器里已经有了就让位"，于是三个来源各登记各的时，后处理的那一个会静默不注册，
 * 它的工具从此一律按未登记被拒（01 号票修过，02 号票又在 MCP 上重演）。鉴权 Advisor 消费的是
 * 一张表的列表，多个登记表本来就是设计上允许的。
 *
 * <p>清单与执行入口分成两个 bean（06 号票）：人审通过的包在运行中被加进清单，而加进清单必须同时
 * 把执行后端建好。分开之后，生成器那层只要拿到 registry 就能完成这件事，不必知道后端怎么建。
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
     * <p>"新包要建后端"这条只挂一次，挂在 {@link ScriptPackageToolProvider} 的构造里。
     * 挂两次的话，注册一个包会触发两次后端注册，第二次撞上"重名即失败"，
     * 报出来的是"已经有一个叫 X 的包"，离"listener 挂重了"隔着一层。
     */
    @Bean
    public ScriptPackageRunner scriptPackageRunner(ScriptPackageRegistry registry) {
        return new ScriptPackageRunner(registry.packages());
    }

    /** 工具来源：按清单现取包，执行结果交给观察者容器。 */
    @Bean
    public ScriptPackageToolProvider scriptPackageToolProvider(ScriptPackageRegistry registry,
                                                               ScriptPackageRunner runner,
                                                               ExecutionListeners executionListeners) {
        return new ScriptPackageToolProvider(registry, runner, executionListeners);
    }

    /**
     * 执行观察者的容器：先落地、后来者往里加（见 {@link ExecutionListeners}）。
     *
     * <p>没有别的来源时它是空的，于是"没接审计"表现为空容器而不是装配失败：审计是留痕手段，
     * 不是这条链路能不能工作的前提。
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

    /**
     * 档位：{@link ToolTier#CODING}，与"跑脚本"这件事同一档。
     *
     * <p>工具名清单取 {@code provider.permissions().keySet()}，不另抄一份：脚本包的名字是
     * {@code examples/scripts} 下的目录名，抄一份就会在加包时漂移，而漂移的表现是启动失败并点名。
     *
     * <p>这是名字清单，不是"任何名字都算这一档"的兜底：后者会让别处的未声明校验永远通过，
     * 而这套机制的价值全在那条校验上。
     */
    @Bean
    public ToolTierRegistry scriptPackageTierRegistry(ScriptPackageToolProvider provider) {
        return ToolTierRegistry.sameFor(provider.permissions().keySet(), ToolTier.CODING);
    }
}
