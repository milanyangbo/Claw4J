package io.github.claw4j.agent.subagent;

import io.github.claw4j.agent.AgentAutoConfiguration;
import io.github.claw4j.agent.ToolSearchProperties;
import io.github.claw4j.agent.ToolsProperties;
import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.observability.SpanTracer;
import io.github.claw4j.agent.persona.AgentProperties;
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
 * <p>默认不启用，与 Skill、脚本两层同一个理由：让 Agent 把事情派出去是一个决定，不是引了依赖
 * 就自动有。没配时容器里连这个 bean 都不存在，于是「清单里为什么没有 Task」有唯一答案：没配。
 *
 * <p>导出的是单个 {@code ToolCallback}：委派本身就是一个工具，派给谁由参数决定。每加一个
 * Subagent 就多一个工具的话，模型面前会越来越挤，而「该派谁」本来就该由描述里那几行回答。
 *
 * <p>权限登记表不用 {@code @ConditionalOnMissingBean(ToolPermissionRegistry.class)}：那条条件
 * 的语义是容器里已经有了就让位，几个来源各登记各的时，后处理的那一个会静默不注册，它的工具
 * 从此一律按「未登记」被拒。鉴权 Advisor 消费的是登记表的列表，不是单张表。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "claw4j.subagents", name = "directory")
public class SubagentAutoConfiguration {

    /**
     * 委派工具。目录不存在、里面没有 {@code *.md}、或某份定义写错，都在启动时炸
     * （{@link SubagentCatalog#load} 与 {@link SubagentDefinition#parse}）。
     *
     * <p>工具集用 {@code Supplier} 惰性取：本方法自己产出的就是一个 {@code ToolCallback} bean，
     * 装配期展开 {@code ObjectProvider<ToolCallback>} 会把正在创建的自己卷进去。展开发生在
     * 委派那一刻就没这个问题。
     *
     * <p>子 Agent 拿不到 {@code Task} 自己：白名单那条路按定义错误处理，继承全部那条路在这里
     * 过滤掉，于是嵌套委派两条路都堵住了。
     *
     * @param directory Subagent 定义目录
     * @param chatModel 子 Agent 用的模型
     * @param toolCallbacks 单回调形态的工具
     * @param toolCallbackProviders provider 形态的工具
     * @param observationRegistry 观测登记表，没有就用 NOOP
     * @param meterRegistry 指标登记表，可为空
     * @param tierRegistries 各来源的档位登记表
     * @param toolPolicy 工具档位策略
     * @param authorizationAdvisor 子链上唯一的 Advisor
     * @param toolIndex 与父共用的工具检索索引
     * @param toolSearch 检索参数
     * @param agent Agent 配置，取工具调用熔断上限
     * @return 委派工具
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
                                             ToolSearchProperties toolSearch,
                                             AgentProperties agent) {
        var definitions = SubagentCatalog.load(Path.of(directory));
        var observations = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
        var runner = new SubagentRunner(chatModel, observations,
                () -> subagentToolCallbacks(toolCallbacks, toolCallbackProviders,
                        meterRegistry.getIfAvailable(), toolPolicy,
                        tierRegistries.orderedStream().toList()),
                authorizationAdvisor,
                toolIndex,
                toolSearch,
                agent.toolCallLimit());
        return new SubagentDelegation(definitions, runner);
    }

    /**
     * 子 Agent 能用的工具集：与父同一个收集方法、同一份档位策略，只去掉 {@code Task} 自己。
     *
     * <p>抽成静态方法是为了让这条逻辑能被直接问（P09）：否则要验「子那边有没有被档位过滤」，
     * 只能起一个完整的上下文再想办法从 runner 里把清单掏出来。可测性在这里不是副产品，它决定了
     * 「父子两处过滤是否一致」能不能被钉住。
     *
     * <p>三个 {@code null} 的理由与父侧不同，逐条记在这里：
     * <ul>
     *   <li>预算：子的工具上下文里没有父这一轮的 runId（{@link SubagentRunner} 目前只把身份
     *       传下去），传了也扣不到，「子调的工具扣父的额度」是 A10 的事；
     *   <li>审计：同理，子链没有 runId，写不进任何一件事的审计行；
     *   <li>指标：由调用方给，没有 actuator 时它就是 {@code null}。
     * </ul>
     *
     * <p>trace 显式传空实现（P14）：子链没有自己的编号，画出来的 {@code tool.*} 会挂在
     * {@code tool.Task} 那段下面却没有对应的审计，只有半截故事；而半截的 trace 比没有更费人，
     * 看的人会以为「子工具没落审计」。要画全它得让 {@link SubagentRunner} 认识这一轮的编号，
     * 那是 A10 的事。
     *
     * @param toolCallbacks 单回调形态的工具
     * @param toolCallbackProviders provider 形态的工具
     * @param meterRegistry 指标登记表，可为空
     * @param toolPolicy 工具档位策略
     * @param tierRegistries 各来源的档位登记表
     * @return 子 Agent 的可用工具，已去掉委派工具自己
     */
    public static List<ToolCallback> subagentToolCallbacks(ObjectProvider<ToolCallback> toolCallbacks,
                                                           ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                                                           MeterRegistry meterRegistry,
                                                           ToolsProperties toolPolicy,
                                                           List<ToolTierRegistry> tierRegistries) {
        // 与 Agent 装配用的是同一个收集方法：工具回调与 provider 两种形态都要收。
        // 只收单回调形态时，querySales（来自 provider）在子那一侧就「没有这个工具」，
        // 而它明明在父的清单里。
        //
        // 档位在这里同一个理由（P09）：子 Agent 拿到的必须是父被允许的那份清单里的一批。
        // 用另一份策略的话，一个 messaging 部署可以靠「派给子 Agent」把手跑脚本的口子打开。
        return AgentAutoConfiguration.collectToolCallbacks(toolCallbacks, toolCallbackProviders,
                        meterRegistry, null, SpanTracer.NONE,
                        toolPolicy.toProfile(), tierRegistries)
                .stream()
                .filter(callback -> !SubagentDelegation.TOOL_NAME.equals(
                        callback.getToolDefinition().name()))
                .toList();
    }

    /**
     * 这一层的权限登记表：动作是委派，业务表一张都没有（ADR-0003）。
     *
     * <p>委派这个动作确实会碰到表，但不是它自己碰：是子 Agent 调的那个工具碰，而那一步在子链上
     * 按自己的登记表鉴权。这里如果替它登记了表，委派就会变成一张能绕过子链鉴权的通行证。
     *
     * @return 只登记委派这一个动作的登记表
     */
    @Bean
    public ToolPermissionRegistry subagentPermissionRegistry() {
        return ToolPermissionRegistry.of(Map.of(
                SubagentDelegation.TOOL_NAME, SubagentDelegation.permission()));
    }

    /**
     * 委派工具的档位：{@link ToolTier#MESSAGING}。
     *
     * <p>派活本身不碰外部世界，但它把「能干什么」交给子 Agent，而子 Agent 拿的是同一份筛过的
     * 清单，所以它不该比直接用工具更受限。{@code MINIMAL} 那种只聊天的部署里没有它。
     *
     * @return 只给委派工具定档的登记表
     */
    @Bean
    public ToolTierRegistry subagentTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(SubagentDelegation.TOOL_NAME), ToolTier.MESSAGING);
    }
}
