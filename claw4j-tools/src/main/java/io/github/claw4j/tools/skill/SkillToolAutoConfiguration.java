package io.github.claw4j.tools.skill;

import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.Map;

/**
 * Skill 这一层的装配：配了 {@code claw4j.skills.directory} 就有这一层，没配就没有。
 *
 * <p><b>默认不启用</b>，与脚本层同一个理由：把"做法"交给模型去选，是一个决定，
 * 不是"引了依赖就自动有"。没有配置时容器里连这个 bean 都不存在，
 * 于是"清单里为什么没有 Skill"这个问题有唯一答案：没配。
 *
 * <p><b>这一层导出的是单个 {@code ToolCallback}</b>，不是 {@code ToolCallbackProvider}：
 * 一个技能目录只产出一个工具（用名字取正文的那种），
 * 所以它是 01 号票那条"两种形态都要收"里的第二种形态。
 *
 * <p><b>权限登记表不用 {@code @ConditionalOnMissingBean(ToolPermissionRegistry.class)}</b>：
 * 那条条件的语义是"容器里已经有了就让位"，于是三个来源各登记各的时，后处理的那一个
 * 会静默不注册——它的工具从此一律按"未登记"被拒。这是 01 号票修掉、02 号票在 MCP 上
 * 又重演一遍的同一个形状。鉴权 Advisor 消费的是一张表的<b>列表</b>。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "claw4j.skills", name = "directory")
public class SkillToolAutoConfiguration {

    /** 技能目录里一个 {@code SKILL.md} 都没有即启动失败（见 {@link SkillCatalog#load}）。 */
    @Bean
    public ToolCallback skillToolCallback(@Value("${claw4j.skills.directory}") String directory) {
        return AgentSkills.callback(Path.of(directory));
    }

    /** 这一层的权限登记表：动作是加载 Skill，业务表一张都没有（ADR-0003）。 */
    @Bean
    public ToolPermissionRegistry skillPermissionRegistry() {
        return ToolPermissionRegistry.of(Map.of(AgentSkills.TOOL_NAME, AgentSkills.permission()));
    }
}
