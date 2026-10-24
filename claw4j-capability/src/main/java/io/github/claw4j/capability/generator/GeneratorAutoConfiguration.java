package io.github.claw4j.capability.generator;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.capability.script.ExecutionListeners;
import io.github.claw4j.capability.script.ScriptPackageAutoConfiguration;
import io.github.claw4j.capability.script.ScriptPackageRegistry;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.time.Clock;

/**
 * 生成器这一层的装配：配了 {@code claw4j.generator.staging-directory} 才有这一层。
 *
 * <p><b>它必须排在脚本层之后，而且要求脚本层存在</b>（{@code @ConditionalOnBean}）：
 * 人审通过之后的注册目标是 {@link ScriptPackageRegistry}，
 * 没有脚本层就没有"注册到哪儿"，于是这一层也不该存在——
 * 否则"人签了字"会变成一个没有任何效果的动作，而它看起来是成功的。
 *
 * <p>审批目录默认落在暂存区<b>旁边</b>而不是里面：自测那份副本够不到它，
 * 这是"未审核代码不能给自己签字"这条边界的物理保证。
 */
@AutoConfiguration(after = ScriptPackageAutoConfiguration.class)
@ConditionalOnProperty(prefix = "claw4j.generator", name = "staging-directory")
@ConditionalOnBean(ScriptPackageRegistry.class)
public class GeneratorAutoConfiguration {

    private static final String STAGING = "${claw4j.generator.staging-directory}";

    /**
     * 五步链路。
     *
     * <p>启动时先跑一次 {@code publishApproved()}：上次运行期间人签过的字，
     * 不该等"下次有人再生成一次"才生效——那会把"人点了一下通过"
     * 变成一次需要重启、或者需要再来一轮对话才发生的事。
     */
    @Bean
    public SkillGenerator skillGenerator(@Value(STAGING) String stagingDirectory,
                                         @Value("${claw4j.generator.review-directory:}") String reviewDirectory,
                                         @Value("${claw4j.generator.published-directory:}") String publishedDirectory,
                                         ScriptPackageRegistry scriptPackageRegistry,
                                         GenerationAudit generationAudit,
                                         Clock claw4jClock) {
        Path staging = Path.of(stagingDirectory).toAbsolutePath().normalize();
        Path reviews = reviewDirectory == null || reviewDirectory.isBlank()
                ? staging.resolveSibling("reviews")
                : Path.of(reviewDirectory).toAbsolutePath().normalize();
        // 发布目录也落在暂存区旁边：上线的那份是快照，不能住在一个模型还能重写的地方。
        Path published = publishedDirectory == null || publishedDirectory.isBlank()
                ? staging.resolveSibling("published")
                : Path.of(publishedDirectory).toAbsolutePath().normalize();
        SkillGenerator generator = new SkillGenerator(staging, reviews, published, scriptPackageRegistry,
                generationAudit, IsolationSpec.local(), claw4jClock);
        generator.publishApproved();
        return generator;
    }

    /**
     * 审计落在暂存区旁边的一个文件里。
     *
     * <p>与脚本的暂存区分开：脚本会被反复重写（模型改一版提一次），
     * 而审计只能追加。放同一个目录里的话，"清一下暂存区"会顺手把审计删了。
     */
    @Bean
    public GenerationAudit generationAudit(@Value(STAGING) String stagingDirectory,
                                           @Value("${claw4j.generator.audit-file:}") String auditFile) {
        Path file = auditFile == null || auditFile.isBlank()
                ? Path.of(stagingDirectory).toAbsolutePath().normalize()
                        .resolveSibling("generation-audit.tsv")
                : Path.of(auditFile).toAbsolutePath().normalize();
        return new GenerationAudit(file);
    }

    @Bean
    public Clock claw4jClock() {
        return Clock.systemUTC();
    }

    /** 生成器这个工具本身。 */
    @Bean
    public ToolCallbackProvider skillGeneratorToolProvider(SkillGenerator generator) {
        return new SkillGeneratorToolProvider(generator);
    }

    /**
     * 这一层的权限登记表：动作是 {@code draft_script}，表一张都没有。
     *
     * <p>不用 {@code @ConditionalOnMissingBean}——那是 01 号票修掉、
     * 又在 MCP 与脚本层各重演一次的形状：多个登记表本来就是设计上允许的，
     * 而"已经有表就让位"会让这一层静默不登记，于是这个工具一律按未登记被拒。
     */
    @Bean
    public ToolPermissionRegistry skillGeneratorPermissionRegistry(SkillGenerator generator) {
        return ToolPermissionRegistry.of(new SkillGeneratorToolProvider(generator).permissions());
    }

    /**
     * 把脚本层的执行接到审计上：已注册的包被真正执行时也要留一条。
     *
     * <p>挂在 {@link ExecutionListeners} 上而不是注入一个 listener，
     * 是因为脚本层先装配：到这里才有人知道"审计是什么"。
     * 那个容器存在的唯一理由就是这个顺序（见它的类注释）。
     */
    @Bean
    public ExecutionListeners generatorExecutionListenerBridge(ExecutionListeners listeners,
                                                               GenerationAudit audit,
                                                               Clock claw4jClock) {
        listeners.add((packageName, context, outcome, output) -> {
            Object value = context == null ? null : context.getContext().get(ContextKeys.PRINCIPAL);
            String who = value instanceof Principal principal ? principal.userId() : "anonymous";
            audit.record(claw4jClock.instant(), who, GenerationAudit.Stage.EXECUTED, packageName,
                    outcome + "：" + output, "ok".equals(outcome));
        });
        return listeners;
    }
}
