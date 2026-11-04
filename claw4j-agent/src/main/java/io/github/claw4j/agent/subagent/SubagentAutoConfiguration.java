package io.github.claw4j.agent.subagent;

import io.github.claw4j.agent.AgentAutoConfiguration;
import io.github.claw4j.agent.ToolSearchProperties;
import io.github.claw4j.agent.ToolsProperties;
import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.observability.RunTracer;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Subagent 这一层的装配：配了 {@code claw4j.subagents.directory} 才有这一层，没配就没有。
 *
 * <p><b>默认不启用</b>，与 Skill / 脚本两层同一个理由：让 Agent 把事情派出去，
 * 是一个决定，不是"引了依赖就自动有"。没配时容器里连这个 bean 都不存在，
 * 于是"清单里为什么没有 Task"这个问题有唯一答案：没配。
 *
 * <p><b>导出的是单个 {@code ToolCallback}</b>：委派本身就是**一个**工具，
 * 派给谁由参数决定——每加一个 Subagent 就多一个工具的话，
 * 模型面前会越来越挤，而"该派谁"这件事本来就该由描述里那几行去回答。
 *
 * <p><b>权限登记表不用 {@code @ConditionalOnMissingBean(ToolPermissionRegistry.class)}</b>：
 * 那条条件的语义是"容器里已经有了就让位"，三个来源各登记各的时后处理的那一个会静默不注册，
 * 它的工具从此一律按"未登记"被拒。这是 01 号票修掉、02/03 号票又各重演一遍的同一个形状。
 * 鉴权 Advisor 消费的是一张表的<b>列表</b>。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "claw4j.subagents", name = "directory")
public class SubagentAutoConfiguration {

    /**
     * 委派工具。目录不存在、里面没有 {@code *.md}、或某份定义写错，都在启动时炸
     * （{@link SubagentCatalog#load} 与 {@link SubagentDefinition#parse}）。
     *
     * <p><b>工具集用 {@code Supplier} 惰性取</b>：本方法自己产出的就是一个
     * {@code ToolCallback} bean，装配期展开 {@code ObjectProvider<ToolCallback>}
     * 会把正在创建的自己卷进去（循环依赖）。展开发生在委派那一刻就没这个问题。
     *
     * <p><b>子 Agent 拿不到 {@code Task} 自己</b>：定义里白名单写了它按定义错误处理
     * （见 {@link SubagentDefinition}），这里再把"继承全部工具"那条路上也出现的它过滤掉，
     * 于是嵌套委派两条路都堵住了。
     */
    @Bean
    public ToolCallback subagentToolCallback(@Value("${claw4j.subagents.directory}") String directory,
                                             ChatModel chatModel,
                                             ObjectProvider<ToolCallback> toolCallbacks,
                                             ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                                             ObjectProvider<ObservationRegistry> observationRegistry,
                                             ObjectProvider<MeterRegistry> meterRegistry,
                                             ObjectProvider<ToolTierRegistry> tierRegistries,
                                             ToolsProperties toolPolicy,
                                             AuthorizationAdvisor authorizationAdvisor,
                                             // 工具循环只有一种（检索），它要索引与检索参数；
                                             // 两个 bean 都由 AgentAutoConfiguration 提供。
                                             ToolIndex toolIndex,
                                             ToolSearchProperties toolSearch) {
        var definitions = SubagentCatalog.load(Path.of(directory));
        var observations = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
        var runner = new SubagentRunner(chatModel, observations,
                () -> subagentToolCallbacks(toolCallbacks, toolCallbackProviders,
                        meterRegistry.getIfAvailable(), toolPolicy,
                        tierRegistries.orderedStream().toList()),
                authorizationAdvisor,
                toolIndex,
                toolSearch);
        return new SubagentDelegation(definitions, runner);
    }

    /**
     * 子 Agent 能用的工具集：与父同一个收集方法、同一份档位策略，只去掉 {@code Task} 自己。
     *
     * <p>抽成静态方法是让这条逻辑**能被直接问**（P09）：否则要验"子那边有没有被档位过滤"，
     * 只能起一个完整的上下文再想办法从 runner 里把清单掏出来。可测性在这里不是副产品，
     * 它决定了"父子两处过滤是否一致"这件事能不能被钉住。
     *
     * <p>三个 {@code null} 的理由与父侧不同，逐条记在这里：
     * <ul>
     *   <li>预算：子的工具上下文里没有父这一轮的 runId（{@link SubagentRunner} 目前只把身份传下去），
     *       传了也扣不到——"子调的工具扣父的额度"是 A10 的事；</li>
     *   <li>审计：同理，子链没有 runId，写不进任何一件事的审计行；</li>
     *   <li>指标：由调用方给，没有 actuator 时它就是 {@code null}。</li>
     * </ul>
     *
     * <p><b>trace 显式传空实现</b>（P14）：子链没有 run，画出来的 {@code tool.*} 会挂在
     * {@code tool.Task} 那段下面、却永远没有 {@code claw4j.step.seq}——只有半截故事，
     * 而半截的 trace 比没有更费人（看的人会以为"子工具没落审计"）。要画全它得让
     * {@link SubagentRunner} 认识 run，那是 A10 的事。
     *
     * <p><b>过滤掉 {@code Task} 自己</b>：定义里白名单写了它按定义错误处理（见
     * {@link SubagentDefinition}），这里再把"继承全部工具"那条路上也出现的它去掉，
     * 于是嵌套委派两条路都堵住了。
     */
    public static List<ToolCallback> subagentToolCallbacks(ObjectProvider<ToolCallback> toolCallbacks,
                                                           ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                                                           MeterRegistry meterRegistry,
                                                           ToolsProperties toolPolicy,
                                                           List<ToolTierRegistry> tierRegistries) {
        // 与 Agent 装配用的是同一个收集方法：工具回调与 provider 两种形态都要收。
        // 只收单回调形态时，querySales（来自 provider）在子那一侧就"没有这个工具"，
        // 而它明明在父的清单里——症状与 01 号票那个一模一样。
        //
        // 档位在这里同一个理由（P09）：子 Agent 拿到的必须是**父被允许的那份清单**里的一批。
        // 用另一份策略的话，一个 messaging 部署可以靠"派给子 Agent"把手跑脚本的口子打开。
        return AgentAutoConfiguration.collectToolCallbacks(toolCallbacks, toolCallbackProviders,
                        meterRegistry, null, null, RunTracer.NONE,
                        toolPolicy.toProfile(), tierRegistries)
                .stream()
                .filter(callback -> !SubagentDelegation.TOOL_NAME.equals(
                        callback.getToolDefinition().name()))
                .toList();
    }

    /**
     * 这一层的权限登记表：动作是委派，业务表一张都没有（ADR-0003）。
     *
     * <p>委派这个动作确实会碰到表——但不是它自己碰，是子 Agent 调的那个工具碰，
     * 而那一步在子链上按自己的登记表鉴权。这里如果替它登记了表，
     * "委派"就会变成一张能绕过子链鉴权的通行证。
     */
    @Bean
    public ToolPermissionRegistry subagentPermissionRegistry() {
        return ToolPermissionRegistry.of(Map.of(
                SubagentDelegation.TOOL_NAME, SubagentDelegation.permission()));
    }

    /**
     * 委派工具的档位：{@link ToolTier#MESSAGING}。
     *
     * <p>派活本身不碰外部世界，但它把"能干什么"这件事交给子 Agent —— 而子 Agent 拿的是
     * 同一份筛过的清单（见上面的 {@code collectToolCallbacks} 调用），所以它不该比
     * "直接用工具"更受限。{@code MINIMAL} 那种"只聊天"的部署里没有它。
     */
    @Bean
    public ToolTierRegistry subagentTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(SubagentDelegation.TOOL_NAME), ToolTier.MESSAGING);
    }
}
