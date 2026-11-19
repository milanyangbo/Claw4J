package io.github.claw4j.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 长期记忆的装配：配了 {@code claw4j.agent.memory.directory} 才有这一层，没配就没有。
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
@ConditionalOnProperty(prefix = "claw4j.agent.memory", name = "directory")
public class MemoryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryAutoConfiguration.class);

    /**
     * 长期记忆 Advisor。
     *
     * <p>它进链靠的是"容器里有 {@code Advisor} 类型的 bean"：{@code AgentAutoConfiguration#chatClient}
     * 用 {@code ObjectProvider<Advisor>} 按 order 收。所以这里不需要改那条装配，也不该改。
     *
     * @param directory 记忆根目录，每个人在它下面一个子目录
     * @return 按身份分发的记忆 Advisor
     */
    @Bean
    @ConditionalOnMissingBean(PrincipalMemoryAdvisor.class)
    public PrincipalMemoryAdvisor principalMemoryAdvisor(
            @Value("${claw4j.agent.memory.directory}") String directory) {
        Path root = Path.of(directory).toAbsolutePath().normalize();
        try {
            // 先按 0700 建；父目录已存在时 createDirectories 不会改它的权限，这正是要的——
            // 根目录归部署方，只有"每个人那一层"是我们建的（由库在首次写入时创建）。
            Files.createDirectories(root, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        }
        catch (UnsupportedOperationException notPosix) {
            try {
                Files.createDirectories(root);
            }
            catch (IOException failure) {
                throw new UncheckedIOException("建不出长期记忆目录：" + root, failure);
            }
        }
        catch (IOException failure) {
            throw new UncheckedIOException("建不出长期记忆目录：" + root, failure);
        }
        log.info("长期记忆已启用：{}（每人一个子目录，模型自己决定读写；落盘是明文，见 A09 的缺口）",
                root);
        return new PrincipalMemoryAdvisor(root);
    }
}
