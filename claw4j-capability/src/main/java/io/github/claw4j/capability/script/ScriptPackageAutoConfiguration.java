package io.github.claw4j.capability.script;

import io.github.claw4j.capability.sandbox.DockerCli;
import io.github.claw4j.capability.sandbox.SandboxBackends;
import io.github.claw4j.capability.sandbox.SandboxProperties;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * 脚本层的装配：配了 {@code claw4j.scripts.directory} 就有这一层，没配就没有。
 *
 * <p>默认不启用。脚本层是三层里唯一能把"执行"这件事放开的一层，它的存在应该是一个决定，
 * 而不是"引了依赖就自动有"，于是开关是"有没有配目录"。
 *
 * <p>这里不能用 {@code @ConditionalOnMissingBean} 卡这道：那条条件的语义是"容器里已经有了
 * 就让位"，于是三个来源各出一个 provider 时，后注册的那一个会静默不出现，它的工具从此在
 * 清单里消失（01 号票修过，02 号票又在 MCP 上重演）。多份来源本来就是设计上允许的：
 * 收集方按列表收。
 *
 * <p>清单与执行入口分成两个 bean（06 号票）：人审通过的包在运行中被加进清单，而加进清单必须同时
 * 把执行后端建好。分开之后，生成器那层只要拿到 registry 就能完成这件事，不必知道后端怎么建。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "claw4j.scripts", name = "directory")
@EnableConfigurationProperties(ScriptPackageProperties.class)
public class ScriptPackageAutoConfiguration {

    /**
     * 包清单：启动时从目录读进来，之后只接受人审通过的包（06 号票）。
     *
     * <p>目录不存在即启动失败（见 {@link ScriptPackageCatalog#load}）：
     * 配了却不存在是配置错误，不是"这一层不存在"。
     */
    @Bean
    public ScriptPackageRegistry scriptPackageRegistry(ScriptPackageProperties properties) {
        return new ScriptPackageRegistry(ScriptPackageCatalog.load(Path.of(properties.directory())));
    }

    /**
     * 执行入口：换沙箱的地方是 {@link ProcessRunnerFactory}，装配时不写死。
     *
     * <p>"新包要建后端"这条只挂一次，挂在 {@link ScriptPackageToolProvider} 的构造里。
     * 挂两次的话，注册一个包会触发两次后端注册，第二次撞上"重名即失败"，
     * 报出来的是"已经有一个叫 X 的包"，离"listener 挂重了"隔着一层。
     */
    @Bean
    public ScriptPackageRunner scriptPackageRunner(ScriptPackageRegistry registry,
                                                   ProcessRunnerFactory processRunnerFactory) {
        return new ScriptPackageRunner(registry.packages(), processRunnerFactory);
    }

    /**
     * 执行后端：宿主那条沙箱子进程，或者另一台容器（P17a）。
     *
     * <p>挑哪一个是 {@link SandboxBackends} 那一处的事，这里只把配置与 CLI 交过去——
     * 判断放在装配里会变成"改一处配置要动三个装配类"，而它们各写一遍 {@code mode} 判断时，
     * 加第三档必然只改一边。
     *
     * <p>{@code mode=off} 时拿到的是 {@link ProcessRunnerFactory#SANDBOXED} 本身，不是包装。
     *
     * @param properties 沙箱配置
     * @param dockerCli  docker CLI；{@code mode=off} 时不存在
     * @return 这一层用的工厂
     */
    @Bean
    @ConditionalOnMissingBean
    public ProcessRunnerFactory processRunnerFactory(SandboxProperties properties,
                                                     ObjectProvider<DockerCli> dockerCli) {
        return SandboxBackends.scriptFactory(properties, dockerCli.getIfAvailable());
    }

    /**
     * 工具来源：按清单现取包，执行结果交给观察者容器。
     *
     * <p>{@code tool-filter} 在这里传进去（票 07）：过滤是"这个包存不存在"，与执行留痕是两件事，
     * 但都归这一层决定。被排掉的包在 callbacks、权限登记、档位登记三处一起消失。
     */
    @Bean
    public ScriptPackageToolProvider scriptPackageToolProvider(ScriptPackageRegistry registry,
                                                               ScriptPackageRunner runner,
                                                               ExecutionListeners executionListeners,
                                                               ScriptPackageProperties properties) {
        return new ScriptPackageToolProvider(registry, runner, executionListeners, properties.toolFilter());
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

    /**
     * 档位：{@link ToolTier#CODING}，与"跑脚本"这件事同一档。
     *
     * <p>工具名清单取 {@code provider.toolNames()}，不另抄一份：脚本包的名字是
     * {@code examples/scripts} 下的目录名，抄一份就会在加包时漂移，而漂移的表现是启动失败并点名。
     * 它同时也是**过滤之后**的那份（票 07）：被 {@code tool-filter} 排掉的包不该有档位登记。
     *
     * <p>这是名字清单，不是"任何名字都算这一档"的兜底：后者会让别处的未声明校验永远通过，
     * 而这套机制的价值全在那条校验上。
     */
    @Bean
    public ToolTierRegistry scriptPackageTierRegistry(ScriptPackageToolProvider provider) {
        return ToolTierRegistry.sameFor(provider.toolNames(), ToolTier.CODING);
    }
}
