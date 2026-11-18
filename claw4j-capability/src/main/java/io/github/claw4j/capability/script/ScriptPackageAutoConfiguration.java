package io.github.claw4j.capability.script;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * 脚本目录的装配：配了 {@code claw4j.scripts.directory} 就把目录读成一份清单。
 *
 * <p><b>2026-11-18：这一层不再是"可执行包"。</b>按 OpenClaw 的形态，技能是"目录 + {@code SKILL.md}
 * + 支撑文件"，本身不被执行；脚本由模型用 {@code Bash} 去跑（`docs/reviews/2026-11-18-open-source-inventory.md`、
 * `.scratch/oss-alignment/issues/02-script-packages-degrade-to-skills.md`）。于是这里不再产出
 * {@code run_script} 这个工具，也不再建执行后端（那条自研的 argv 执行器整包删掉了）。
 *
 * <p>清单还留着，只有一个用处：生成器那层要判断"这个名字是不是已经被别的来源占了、这份内容是不是
 * 已经发布过"（{@link ScriptPackageRegistry}）。它是账本，不是执行入口。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "claw4j.scripts", name = "directory")
@EnableConfigurationProperties(ScriptPackageProperties.class)
public class ScriptPackageAutoConfiguration {

    /**
     * 包清单：启动时从目录读进来。
     *
     * <p>目录不存在即启动失败（见 {@link ScriptPackageCatalog#load}）：
     * 配了却不存在是配置错误，不是"这一层不存在"。
     *
     * @param properties {@code claw4j.scripts.*} 的配置
     * @return 读好的清单
     */
    @Bean
    public ScriptPackageRegistry scriptPackageRegistry(ScriptPackageProperties properties) {
        return new ScriptPackageRegistry(ScriptPackageCatalog.load(Path.of(properties.directory())));
    }

    /**
     * 执行观察者的容器：先落地、后来者往里加（见 {@link ExecutionListeners}）。
     *
     * <p>没有别的来源时它是空的，于是"没接审计"表现为空容器而不是装配失败：审计是留痕手段，
     * 不是这条链路能不能工作的前提。
     *
     * @return 空的观察者容器
     */
    @Bean
    @ConditionalOnMissingBean
    public ExecutionListeners executionListeners() {
        return new ExecutionListeners();
    }
}
