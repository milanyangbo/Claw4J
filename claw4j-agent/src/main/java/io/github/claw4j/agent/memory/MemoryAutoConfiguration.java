package io.github.claw4j.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springaicommunity.agent.dream.AutoDreamService;
import org.springaicommunity.agent.dream.DreamTrigger;
import org.springaicommunity.agent.dream.DreamTriggers;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 长期记忆的装配：配了 {@code claw4j.advisor.memory.directory} 才有这一层，没配就没有。
 *
 * <p>与 {@code claw4j.skills.directory} / {@code claw4j.subagents.directory} 同一个口径——
 * <b>不给默认目录</b>。给一个默认值等于默认打开这一层：记忆会落盘、会跨会话累积，而"我没想到它会
 * 记东西"这件事必须在配置里说清楚过。
 *
 * <p>根目录在启动期创建（不是第一次写入时）：建不出来、或没有写权限，现在就该炸。等到模型第一次
 * 调 {@code MemoryCreate} 才失败的话，那次失败表现为"记忆没存上"，而这句话在一轮对话里跟
 * "模型决定不记"长得一模一样。
 *
 * <p>目录权限按 0700 建：它装的是这个人的偏好与项目事实，同机上别的进程不需要读。
 * 非 POSIX 文件系统（Windows）跳过这一句——那里没有这个概念，也不该因此起不来。
 */
@AutoConfiguration(afterName = "io.github.claw4j.agent.AgentAutoConfiguration")
@ConditionalOnClass(AutoMemoryToolsAdvisor.class)
@ConditionalOnProperty(prefix = "claw4j.advisor.memory", name = "directory")
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryAutoConfiguration.class);

    /** "距上次整理够久了"那一半条件：小时。 */
    private static final long DREAM_AFTER_HOURS = 24;

    /** "又攒够会话了"那一半条件：轮。两个都满足才跑，见 {@code DreamTriggers#hoursAndSessions}。 */
    private static final int DREAM_AFTER_SESSIONS = 5;

    /**
     * 长期记忆 Advisor：读写记忆那半边。
     *
     * <p>它进链靠的是"容器里有 {@code Advisor} 类型的 bean"：{@code AgentAutoConfiguration#chatClient}
     * 用 {@code ObjectProvider<Advisor>} 按 order 收。所以这里不需要改那条装配，也不该改。
     *
     * @param properties 记忆的可调项
     * @return 按身份分发的记忆 Advisor
     */
    @Bean
    @ConditionalOnMissingBean(PrincipalMemoryAdvisor.class)
    public PrincipalMemoryAdvisor principalMemoryAdvisor(MemoryProperties properties) {
        return new PrincipalMemoryAdvisor(memoryRoot(properties));
    }

    /**
     * 记忆整理 Advisor：到点整理那半边。
     *
     * <p>与上面那份同一个根目录、同一个 order 段：{@code before} 空着，只挂 {@code after}。
     * 关掉整理（{@code claw4j.advisor.memory.dream=false}）时给库的 {@code manualOnly}——
     * 也就是这条路上什么都不发生，而不是"我们自己写一个空实现"。
     *
     * @param properties 记忆的可调项
     * @param dreamService 整理服务
     * @param dreamTaskRepository 整理跑在哪张后台任务表上
     * @return 按身份分发的整理 Advisor
     */
    @Bean
    @ConditionalOnMissingBean(PrincipalDreamAdvisor.class)
    public PrincipalDreamAdvisor principalDreamAdvisor(MemoryProperties properties,
                                                       AutoDreamService dreamService,
                                                       @Qualifier("dreamTaskRepository")
                                                       TaskRepository dreamTaskRepository) {
        DreamTrigger trigger = properties.dream()
                ? DreamTriggers.hoursAndSessions(DREAM_AFTER_HOURS, DREAM_AFTER_SESSIONS)
                : DreamTriggers.manualOnly();
        log.info("记忆整理：{}", properties.dream()
                ? "开（同一人距上次 " + DREAM_AFTER_HOURS + " 小时且又过 " + DREAM_AFTER_SESSIONS
                        + " 轮才跑一次，库的 dream 周期）"
                : "关（读写记忆照旧，只是不合并也不精简）");
        return new PrincipalDreamAdvisor(memoryRoot(properties), dreamService,
                dreamTaskRepository, trigger);
    }

    /**
     * 整理服务：库的 {@code AutoDreamService}，跑一次就是"带着记忆工具进那个目录合并与精简"。
     *
     * <p>它建的 {@code ChatClient} 不挂任何 Advisor：dreamer 是后台的一次独立调用，看不到当前
     * 对话，也不该被会话记忆、脱敏、语义缓存那几层影响——那些层验的是"跟用户的这一段"，不是
     * "整理这个人的记忆文件"。
     *
     * @param chatModel 主模型
     * @return 整理服务，所有用户共用一份
     */
    @Bean
    @ConditionalOnMissingBean(AutoDreamService.class)
    public AutoDreamService dreamService(ChatModel chatModel) {
        return AutoDreamService.builder(ChatClient.builder(chatModel)).build();
    }

    /**
     * 整理用的后台任务表。
     *
     * <p>单独一张，不与子 Agent 共用：那张只在配了 {@code claw4j.subagents.directory} 时才有，
     * 而记忆整理跟子 Agent 没关系。线程池由它自己持有，容器关闭时关掉。
     *
     * @return 后台任务表
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "dreamTaskRepository")
    public TaskRepository dreamTaskRepository() {
        return new DefaultTaskRepository();
    }

    /**
     * 记忆根目录：配了才走到这里，启动期就建出来。
     *
     * <p>按 0700 建：它装的是这个人的偏好与项目事实，同机上别的进程不需要读。非 POSIX 文件系统
     * （Windows）跳过这一句——那里没有这个概念，也不该因此起不来。
     *
     * <p>建不出来、或没有写权限，现在就该炸：等到模型第一次调 {@code MemoryCreate} 才失败的话，
     * 那次失败表现为"记忆没存上"，而这句话在一轮对话里跟"模型决定不记"长得一模一样。
     *
     * @param properties 记忆的可调项
     * @return 根目录
     */
    private static Path memoryRoot(MemoryProperties properties) {
        Path root = Path.of(properties.directory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        }
        catch (UnsupportedOperationException notPosix) {
            createPlainly(root);
        }
        catch (IOException failure) {
            throw new UncheckedIOException("建不出长期记忆目录：" + root, failure);
        }
        log.info("长期记忆已启用：{}（每人一个子目录，模型自己决定读写；落盘是明文，见 A13 的缺口）",
                root);
        return root;
    }

    private static void createPlainly(Path root) {
        try {
            Files.createDirectories(root);
        }
        catch (IOException failure) {
            throw new UncheckedIOException("建不出长期记忆目录：" + root, failure);
        }
    }
}
