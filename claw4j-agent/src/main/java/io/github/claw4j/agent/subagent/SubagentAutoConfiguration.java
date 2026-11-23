package io.github.claw4j.agent.subagent;

import io.github.claw4j.domain.port.Approvals;
import io.github.claw4j.domain.principal.ToolProfile;
import io.github.claw4j.domain.principal.ToolPolicyPipeline;
import io.github.claw4j.domain.principal.ToolPolicyContext;
import io.github.claw4j.agent.toolaccess.ToolPolicyAssembler;
import io.github.claw4j.agent.AgentAutoConfiguration;
import io.github.claw4j.agent.approval.ApprovalRequiredTools;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.toolaccess.ToolSearchProperties;
import io.github.claw4j.agent.toolaccess.ToolsProperties;
import io.github.claw4j.agent.observability.SpanTracer;
import io.github.claw4j.agent.persona.AgentProperties;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * <p>子链的清单收窄走 {@link ToolPolicyAssembler}（ADR-0030）；权限登记表那一层已在票 04 下线，
 * 那条条件
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
     * @param toolPolicy 工具画像策略
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
                                             // 工具循环只有一种（检索），它要索引与检索参数；
                                             // 两个 bean 都由 AgentAutoConfiguration 提供。
                                             ToolIndex toolIndex,
                                             ToolSearchProperties toolSearch,
                                             AgentProperties agent,
                                             ObjectProvider<Approvals> approvals,
                                             ObjectProvider<ApprovalRequiredTools> required,
                                             ObjectProvider<ToolCallAudit> audit,
                                             @Qualifier("subagentTaskRepository")
                                             TaskRepository taskRepository) {
        var observations = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
        // 审计缺席（没装持久化 / 没开审计）时传 null：委派照常工作，只是不记账（票 08）。
        var auditSink = audit.getIfAvailable();
        var runner = new SubagentRunner(chatModel, observations, auditSink,
                () -> subagentToolCallbacks(toolCallbacks, toolCallbackProviders,
                        meterRegistry.getIfAvailable(), toolPolicy,
                        tierRegistries.orderedStream().toList(),
                        approvals.getIfAvailable(),
                        required.getIfAvailable(ApprovalRequiredTools::none),
                        auditSink, observations),
                toolIndex,
                toolSearch,
                agent.toolCallLimit());
        // 工具的形状（名字、TaskCall 的 schema、描述模板、按 subagent_type 分派、后台任务）
        // 全部来自库的 TaskTool；这里只填它留的两个扩展点：怎么按引用读定义、怎么执行一次委派。
        // kind 用我们自己的，于是库里那四份内置 Claude 定义不会被塞进来。
        return delegationTool(Path.of(directory), runner, taskRepository);
    }

    /**
     * 按库的形状装出 {@code Task}：一份定义目录 + 一个执行器 + 一张后台任务表。
     *
     * <p>抽成静态方法是为了让"装配怎么装"只有一份：用例要验工具形状、后台任务、身份传递，
     * 都得走这条真实的装配路径；另搭一条只会验出一个和线上不一样的形状。
     *
     * @param directory 定义目录
     * @param runner 委派的执行器
     * @param taskRepository 后台任务表（{@code Task} 与 {@code TaskOutput} 必须共用同一张）
     * @return 委派工具
     */
    public static ToolCallback delegationTool(Path directory, SubagentRunner runner,
                                              TaskRepository taskRepository) {
        ToolCallback task = TaskTool.builder()
                .subagentTypes(new SubagentType(new SubagentReferenceResolver(), runner))
                .subagentReferences(SubagentCatalog.references(directory))
                .taskRepository(taskRepository)
                .taskDescriptionTemplate(DESCRIPTION_TEMPLATE)
                .build();
        // 身份要穿过库那一层（它拿不到 ToolContext），所以外面套一层——只做这一件事。
        return new SubagentDelegation(task);
    }

    /**
     * 后台任务的取结论工具：{@code Task} 用 {@code run_in_background} 派出去之后，模型靠它拿结果。
     *
     * <p>与 {@code Task} 共用同一个 {@link TaskRepository}：库靠 task_id 在表里找，两个工具各拿
     * 一份表的话，任务会"派出去了但查不到"。
     *
     * @param taskRepository 与委派工具共用的任务表
     * @return 名为 {@code TaskOutput} 的工具回调
     */
    @Bean
    public ToolCallback subagentOutputToolCallback(
            @Qualifier("subagentTaskRepository") TaskRepository taskRepository) {
        return TaskOutputTool.builder().taskRepository(taskRepository).build();
    }

    /**
     * 后台任务表：外面包一层"把身份带进那个线程"。
     *
     * <p>库默认那个在别的线程上跑，而身份在 {@link SubagentDelegation} 的 {@link ThreadLocal}
     * 里——不包这一层，后台派出去的子 Agent 会被判成"没有携带身份"并拒绝，而同步路径好好的。
     *
     * @return 与两个工具共用的任务表
     */
    @Bean(destroyMethod = "shutdown")
    public TaskRepository subagentTaskRepository() {
        return new SubagentTaskRepository();
    }

    /**
     * 子 Agent 的角色。目前只有一个取值：它们都是叶子，不会再派一层。
     *
     * <p>留成字段而不是内联字符串，是因为策略层按角色名认领（{@code subagent:leaf}）——
     * 将来真出现"能再派一层"的角色时，改的是这里与 {@code ToolPolicyAssembler} 的那张表。
     */
    private static final String SUBAGENT_ROLE = "leaf";

    /**
     * 委派这一对工具的名字：{@code Task} 派活、{@code TaskOutput} 取后台结论。
     *
     * <p>子 Agent 两个都拿不到：它是一次性执行体，让它再派一层会让预算与隔离变成递归，
     * 让它查父的后台任务则会看见另一条分支的中间产物。
     */
    private static final List<String> DELEGATION_TOOLS =
            List.of(SubagentDelegation.TOOL_NAME, "TaskOutput");

    /**
     * 委派工具的描述模板。
     *
     * <p>库自带一份，但那份是给 Claude 那套用的（讲 resume、讲"一次消息里发多个 Task"、
     * 举的例子是 code-reviewer）。我们保留自己的说法，只把库的 {@code %s} 那个位置用来
     * 放注册表——它由 {@code TaskTool.Builder#build()} 填成每条定义的
     * {@code -名字: /一句说明}。
     */
    private static final String DESCRIPTION_TEMPLATE = """
            把一个需要好几步的任务交给专职 Subagent 去做。
            它在独立上下文里自己跑，只把结论回传给你——中间调了什么工具、拿到什么，你看不到。
            需要"自己查、自己核对、再给结论"这类事情时派它，别自己一步步做。

            可用的 Subagent：
            %s

            参数：subagent_type 用上面清单里的名字；prompt 把要它做的事写完整，
            包括口径与"最后给我什么形式"，因为它看不到我们的对话。
            要并行，就在同一条消息里发多个 Task；要后台跑，写 run_in_background=true，
            之后用 TaskOutput 拿结论。
            """;

    /**
     * 子 Agent 能用的工具集：与父同一个收集方法、同一份档位策略，只去掉 {@code Task} 自己。
     *
     * <p>抽成静态方法是为了让这条逻辑能被直接问（P09）：否则要验「子那边有没有被档位过滤」，
     * 只能起一个完整的上下文再想办法从 runner 里把清单掏出来。可测性在这里不是副产品，它决定了
     * 「父子两处过滤是否一致」能不能被钉住。
     *
     * <p>三个可空参数的理由与父侧不同，逐条记在这里：
     * <ul>
     *   <li>预算：子的工具上下文里没有父这一轮的 runId（子有自己的那一轮，见票 08），
     *       传了也扣不到，「子调的工具扣父的额度」是 A10 的事；
     *   <li>审计：由调用方给（票 08）。线上那一路传容器里的 {@code ToolCallAudit}，子 Agent 于是
     *       有自己的 run 与自己的工具行；传 {@code null}（裁剪部署、没装持久化）时与以前一样不记账；
     *   <li>指标：由调用方给，没有 actuator 时它就是 {@code null}。
     * </ul>
     *
     * <p>trace 显式传空实现（P14）：子链没有自己的根 span，画出来的 {@code tool.*} 会挂在
     * {@code tool.Task} 那段下面却没有对应的运行段，只有半截故事；而半截的 trace 比没有更费人。
     * 要画全它得让 {@link SubagentRunner} 开自己的 span，那是 A10 的事。
     *
     * @param toolCallbacks 单回调形态的工具
     * @param toolCallbackProviders provider 形态的工具
     * @param meterRegistry 指标登记表，可为空
     * @param toolPolicy 工具画像策略
     * @param tierRegistries 各来源的档位登记表
     * @return 子 Agent 的可用工具，已去掉委派工具自己
     */
    public static List<ToolCallback> subagentToolCallbacks(ObjectProvider<ToolCallback> toolCallbacks,
                                                           ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                                                           MeterRegistry meterRegistry,
                                                           ToolsProperties toolPolicy,
                                                           List<ToolTierRegistry> tierRegistries) {
        return subagentToolCallbacks(toolCallbacks, toolCallbackProviders, meterRegistry, toolPolicy,
                tierRegistries, null, ApprovalRequiredTools.none(), null, ObservationRegistry.NOOP);
    }

    /**
     * 同上，带审批闸。
     *
     * <p>子链也要这道闸：委派不提权，父那边要人批的工具，派给子 Agent 之后同样要人批——否则
     * "先派一个子 Agent 再让它调"就是绕过审批的那条路。闸只对 {@code approvals} 里列出的工具生效，
     * 而 exec 自己那一层闸在工具内部，两处不重叠。
     *
     * @param toolCallbacks 单回调形态的工具
     * @param toolCallbackProviders provider 形态的工具
     * @param meterRegistry 指标登记表，可为空
     * @param toolPolicy 工具画像策略
     * @param tierRegistries 各来源的档位登记表
     * @param approvals 审批，可为 {@code null}（没有就不套闸）
     * @param required 要人批的名单，不可为 {@code null}（没有就是空）
     * @return 子 Agent 的可用工具
     */
    public static List<ToolCallback> subagentToolCallbacks(ObjectProvider<ToolCallback> toolCallbacks,
                                                           ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                                                           MeterRegistry meterRegistry,
                                                           ToolsProperties toolPolicy,
                                                           List<ToolTierRegistry> tierRegistries,
                                                           Approvals approvals,
                                                           ApprovalRequiredTools required) {
        return subagentToolCallbacks(toolCallbacks, toolCallbackProviders, meterRegistry, toolPolicy,
                tierRegistries, approvals, required, null, ObservationRegistry.NOOP);
    }

    /**
     * 完整的那一份：加上审计与观测。
     *
     * <p>审计与观测是配套的两个参数（票 08）：审计给了、观测是 NOOP 时，工具行落得下去但
     * {@code tool_call_id} 恒空（票 06 的坑：NOOP 下当前观测永远是 null）；观测给了、审计为
     * {@code null} 时编号有值却没地方写。线上那一路两个都给。
     *
     * @param toolCallbacks 单回调形态的工具
     * @param toolCallbackProviders provider 形态的工具
     * @param meterRegistry 指标登记表，可为空
     * @param toolPolicy 工具画像策略
     * @param tierRegistries 各来源的档位登记表
     * @param approvals 审批，可为 {@code null}
     * @param required 要人批的名单，不可为 {@code null}
     * @param audit 审计接缝，可为 {@code null}（不记账）
     * @param observations 观测注册表，可为 {@code null}（按 NOOP 处理）
     * @return 子 Agent 的可用工具
     */
    public static List<ToolCallback> subagentToolCallbacks(ObjectProvider<ToolCallback> toolCallbacks,
                                                           ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                                                           MeterRegistry meterRegistry,
                                                           ToolsProperties toolPolicy,
                                                           List<ToolTierRegistry> tierRegistries,
                                                           Approvals approvals,
                                                           ApprovalRequiredTools required,
                                                           ToolCallAudit audit,
                                                           ObservationRegistry observations) {
        // 与 Agent 装配用的是同一个收集方法：工具回调与 provider 两种形态都要收。
        // 只收单回调形态时，querySales（来自 provider）在子那一侧就「没有这个工具」，
        // 而它明明在父的清单里。
        //
        // 档位在这里同一个理由（P09）：子 Agent 拿到的必须是父被允许的那份清单里的一批。
        // 用另一份策略的话，一个 messaging 部署可以靠「派给子 Agent」把手跑脚本的口子打开。
        // 子 Agent 那一层（ADR-0030 裁决二的第 10 层）：角色带来的固定拒绝清单 + 配置的
        // tools.subagents.allow/deny。角色目前只有一个取值——我们的子 Agent 都是叶子，
        // 不会再派一层（那件事由下面的 Task 过滤与 SubagentDefinition 各自兜一次）。
        ToolPolicyContext ctx = ToolPolicyContext.anonymous().withSubagentRole(SUBAGENT_ROLE);
        ToolPolicyAssembler.Decision decision = ToolPolicyAssembler.decide(toolPolicy, ctx);
        ToolProfile profile = toolPolicy.toProfile();
        ToolProfile effective = decision.profile() == profile.profile()
                ? profile
                : new ToolProfile(decision.profile(), profile.allow(), profile.deny(), profile.alsoAllow());
        List<ToolCallback> collected = AgentAutoConfiguration.collectToolCallbacks(
                toolCallbacks, toolCallbackProviders,
                // 子链也有账本可写了（票 08）：每一次委派都是自己的一轮，工具的 started/finished
                // 落在那个 runId 上。观测同时给真的，工具行才带得出这一次的调用编号。
                meterRegistry, audit, SpanTracer.NONE,
                observations == null ? ObservationRegistry.NOOP : observations,
                effective, tierRegistries,
                new ToolPolicyPipeline(decision.layers()),
                selection -> { });
        // 审批闸也接同一个审计：被拒的子调用同样是"发生过的调用"，与父链一个口径。
        return AgentAutoConfiguration.withApprovalGate(collected, approvals, required, audit)
                .stream()
                .filter(callback -> !DELEGATION_TOOLS.contains(callback.getToolDefinition().name()))
                .toList();
    }

    /**
     * 委派工具的画像：只有 {@code coding}。
     *
     * <p>子 Agent 是<b>再开一个能动手的执行体</b>，与 OpenClaw 的 {@code subagents} 同一条：
     * 那个工具只在 {@code coding} 里。子 Agent 拿的是父筛过的那份清单（所以不会越权），
     * 但"这个部署允不允许自己派活"与"派出去之后能做什么"是两件事——前者就是这里的画像。
     *
     * @return 只给委派工具定画像的登记表
     */
    @Bean
    public ToolTierRegistry subagentTierRegistry() {
        return ToolTierRegistry.sameFor(DELEGATION_TOOLS, ToolTier.CODING);
    }
}
