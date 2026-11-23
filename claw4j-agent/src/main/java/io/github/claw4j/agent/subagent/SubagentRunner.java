package io.github.claw4j.agent.subagent;

import io.github.claw4j.agent.audit.AuditErrorCode;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.AuditStatus;
import io.github.claw4j.agent.audit.RunOutcome;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.turn.ToolCallBudget;
import io.github.claw4j.agent.toolaccess.ToolSearch;
import io.github.claw4j.agent.toolaccess.ToolSearchProperties;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.untrusted.PromptData;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.common.task.subagent.SubagentExecutor;
import org.springaicommunity.agent.common.task.subagent.TaskCall;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.toolsearch.ToolIndex;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 委派的执行器：为一次委派开一个独立上下文窗口，跑完只把结论文本交回父 Agent。
 *
 * <p>做深的是上下文隔离，不是起了第二个 Agent（ADR-0008）：后者配一个新 {@code ChatClient}
 * 就有，前者才有断言可写。隔离落在三处，每处都可测：
 * <ul>
 *   <li>子窗口是新建的：这里每次委派都从头 build 一个 {@code ChatClient}，不带父的任何一条
 *       消息，子只拿到 {@link #delegate} 的 {@code task}。
 *   <li>回传的只有 {@code content()}：子中间调了几次工具、每次拿到什么，全留在子窗口里。
 *   <li>子链上不挂记忆与脱敏：记忆会把子的中间过程写进会话，等于把刚隔离掉的东西从另一个门
 *       漏回去；脱敏在父链出站前还会做一次，子在中间打码只会让它认不出自己查到的值。
 * </ul>
 *
 * <p>委派不提权：子拿的是父筛过的那份清单再收窄一层（ADR-0030 裁决二），用的就是父那个
 * {@link Principal}——身份由委派时的入站请求给，不由 Subagent 定义给。父看不到的表，
 * 派个 Subagent 去看这条路从设计上不存在。
 *
 * <p>审计上它是<b>另一轮</b>（票 08）：每次委派生成自己的 {@code runId}，两条运行事件与子链那些
 * 工具事件都挂在这一个编号上，{@code sessionKey} 是 {@code subagent:<定义名>}、{@code agentId} 是
 * 定义名。与父的关系不做推断——OpenClaw 的 lineage 投影要精确父令牌，拿不到就明说缺失，我们也一样。
 * 这样按 {@code sessionKey} 就能问"这个子 Agent 干了什么"，而不必先知道它属于哪一次对话。
 *
 * <p>子吃同一份熔断上限（3 次 / {@code claw4j.agent.tool-call-limit}，见
 * {@link ToolCallBudget}），也走同一个工具循环（{@link ToolSearch#toolLoop}）：委派不能变成
 * 绕过预算的口子，否则一个坏掉的脚本在子里循环能一直烧下去，而父那一侧的预算看起来还一点
 * 没动。子那边同样是先检索、再调用，白名单里的工具先被索引进 {@link ToolIndex}，于是白名单
 * 在检索这条路上也成立。
 *
 * <p>子窗口的会话 id 按定义名固定：工具循环按会话 id 分桶存索引，而它每个请求都会新建实例
 * （{@code DefaultChatClient} 每轮 {@code copy().build()}），LRU 只在实例内生效，真正长期
 * 持有的是共享的 {@link ToolIndex}。每次委派生成新 id 会让桶按委派次数增长且永不回收，
 * 按定义名固定则桶数等于定义的个数。代价是同一份定义的两个并发委派共用一个桶，与同一个用户
 * 同时发两条消息是同一类情形。
 *
 * <p>工具集在这里才取，而且是 {@code Supplier}：装配期取会撞上循环依赖——本 Runner 是
 * {@code Task} 工具回调的依赖，而 {@code Task} 自己又是一个 {@code ToolCallback} bean，
 * {@code ObjectProvider} 在装配期展开就把自己也卷了进去。代价是白名单点名了一个没装上的工具
 * 时，失败发生在首次委派而不是启动时（见 {@link SubagentDefinition#selectTools}）。
 */
public final class SubagentRunner implements SubagentExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubagentRunner.class);

    /** 子 Agent 没能给出任何结论时的回传文本：父至少要知道派出去但没拿到东西。 */
    private static final String EMPTY_CONCLUSION = "（这个 Subagent 没有给出结论）";

    /**
     * 子链会话键的前缀。
     *
     * <p>审批那一侧靠它认"这是子 Agent 的会话，拒绝不投递"（会话键里带 {@code subagent:} 的那一段）。
     * 写成常量而不是在两处各拼一次：改了拼接方式却只改一处的后果，是拒绝开始往子会话里投。
     */
    static final String SESSION_PREFIX = "subagent:";

    private final ChatModel model;

    private final ObservationRegistry observations;

    private final Supplier<List<ToolCallback>> availableTools;

    /** 工具检索索引：与父那条链共用同一个 bean，桶按会话 id 分开（见类注释）。 */
    private final ToolIndex toolIndex;

    private final ToolSearchProperties toolSearch;

    /** 一轮的工具调用熔断（与父同一份配置）：委派不该变成绕过它的口子。 */
    private final int toolCallLimit;

    /**
     * 审计接缝（票 08）：子 Agent 那一轮的两条运行事件从这里写。
     *
     * <p>为 {@code null} 表示这个部署没装持久化那一层（或没开审计）：委派照常工作，只是不记账。
     * 与父链同一个安排——记账不是执行的前置条件。
     */
    private final ToolCallAudit audit;

    public SubagentRunner(ChatModel model,
                          ObservationRegistry observations,
                          ToolCallAudit audit,
                          Supplier<List<ToolCallback>> availableTools,
                          ToolIndex toolIndex,
                          ToolSearchProperties toolSearch,
                          int toolCallLimit) {
        this.model = Objects.requireNonNull(model, "model");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.audit = audit;
        this.availableTools = Objects.requireNonNull(availableTools, "availableTools");
        this.toolIndex = Objects.requireNonNull(toolIndex, "toolIndex");
        this.toolSearch = Objects.requireNonNull(toolSearch, "toolSearch");
        this.toolCallLimit = toolCallLimit;
    }

    /**
     * 这一种执行器对应哪一类定义。库按它把定义分给执行器，我们只有一类。
     *
     * @return {@link SubagentDefinition#KIND}
     */
    @Override
    public String getKind() {
        return SubagentDefinition.KIND;
    }

    /**
     * 库的入口：它不认识我们的 {@code delegate}，只认识这个。
     *
     * <p>身份从 {@link SubagentDelegation#currentPrincipal()} 取——它由外层那层装饰器在一次
     * 工具调用期间放好。取不到就返回一句明确的拒绝而不是照常派：委派一旦脱离身份，子链上的
     * 会话、审计与审批就都说不清是谁做的。
     *
     * @param call 库解析好的参数（{@code prompt} 是正文，其余字段我们不用）
     * @param definition 由 {@link SubagentReferenceResolver} 解析出来的那一份
     * @return 子的结论，或一句拒收
     */
    @Override
    public String execute(TaskCall call, org.springaicommunity.agent.common.task.subagent.SubagentDefinition definition) {
        Principal principal = SubagentDelegation.currentPrincipal();
        if (principal == null) {
            return SubagentDelegation.DENIAL_PREFIX + "：本次委派没有携带身份，一律不放行。";
        }
        String prompt = call.prompt() == null ? "" : call.prompt().trim();
        if (prompt.isEmpty()) {
            // 库的 TaskCall 每个字段都可空：缺 prompt 时不能当成"派一个空任务"照跑，
            // 子 Agent 看不到父的对话，空的 prompt 等于让它猜。
            return "委派要写清 prompt：让那个 Subagent 做什么，包括口径与「最后给我什么形式」。";
        }
        if (!(definition instanceof SubagentDefinition ours)) {
            throw new IllegalStateException("这个执行器只认 " + SubagentDefinition.KIND
                    + " 那一类定义，拿到的是 " + (definition == null ? "null" : definition.getKind())
                    + "。");
        }
        // 子 Agent 的结论是"一份报告，供你综合"，不是"新的任务"。包成数据块、label 带上是谁报的：
        // 它是这一段上下文里最像结论的东西，也正因如此最需要标出来源（与换库之前的形状一致）。
        String report = delegate(ours, prompt, principal);
        String block = PromptData.wrapDataBlock("Subagent result: " + ours.name(), report, 0);
        return block.isEmpty() ? "Subagent 没有返回内容。" : block;
    }

    /**
     * 派一个 Subagent 去做这件事，只把结论带回来。
     *
     * @param definition 派哪一个，决定系统提示词、工具白名单与会话桶
     * @param task 交给子 Agent 的完整任务描述。它看不到父的对话，要什么就得在这里写清
     * @param principal 父这一轮的身份，子链上的鉴权与熔断都用它
     * @return 子的结论；子没有给出任何文本时是一句固定的说明
     */
    public String delegate(SubagentDefinition definition, String task, Principal principal) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(principal, "principal");

        // 子 Agent 是另一个执行体，开的是**自己那一轮**（票 08）：不与父共享 runId。OpenClaw 的原话是
        // "the child owns a new context; it never reuses or mutates the parent context"，它与父的关系
        // 只由 lineage 投影表达——而那条投影要的父令牌我们没有，于是不编一个（见票 08 的缺口）。
        String runId = UUID.randomUUID().toString();
        AuditEvent.Scope scope = AuditEvent.Scope.of(runId, SESSION_PREFIX + definition.name(),
                null, definition.name(), principal, null);
        if (audit != null) {
            audit.runStarted(scope);
        }
        AuditStatus status = AuditStatus.SUCCEEDED;
        AuditErrorCode errorCode = null;
        try {
            return run(definition, task, principal, scope);
        }
        catch (RuntimeException failure) {
            // 与父链同一套判据（RunOutcome）：超时 / 取消 / 普通失败分三档。不另写一份——
            // 两份判据的分歧不会报错，只会让「子那边被取消了几次」永远查不出来。
            RunOutcome outcome = RunOutcome.of(failure);
            status = outcome.status();
            errorCode = outcome.errorCode();
            throw failure;
        }
        finally {
            // 写在 finally：子链炸掉时如果只有 started 那一行，30 分钟后它会被孤儿扫尾补成
            // tool/run 的 unknown——那是"不知道结果"，而这里明明知道。
            if (audit != null) {
                audit.runFinished(scope, status, errorCode);
            }
        }
    }

    /**
     * 真正跑一趟：选工具、建子窗口、调模型。
     *
     * <p>单独一个方法是为了让 {@link #delegate} 里只剩「这一轮从哪开始、到哪里结束」——
     * 运行的两条审计事件必须成对，而它们不该被中间那十几行建窗口的代码隔开。
     *
     * @param definition 派哪一个
     * @param task 交给子 Agent 的任务描述
     * @param principal 父这一轮的身份
     * @param scope 子这一轮的作用域（runId / 会话 / 归属）
     * @return 子的结论
     */
    private String run(SubagentDefinition definition, String task, Principal principal,
                       AuditEvent.Scope scope) {
        List<ToolCallback> tools = definition.selectTools(availableTools.get());
        log.debug("委派 task 给 Subagent {}：它这趟能用的工具是 {}",
                definition.name(),
                tools.stream().map(callback -> callback.getToolDefinition().name()).toList());

        ChatClient subagent = ChatClient.builder(model, observations, null, null,
                        ToolSearch.toolLoop(toolSearch, toolIndex, observations, toolCallLimit))
                .defaultSystem(definition.systemPrompt())
                .defaultToolCallbacks(tools)
                // 记忆与脱敏都不进子链（理由见类注释）。原来还挂着一个鉴权 Advisor，它在
                // ADR-0030 里随权限登记表一起下线：子链能调什么已经由上面那份 tools 决定，
                // 多一道"再判一次权限"只会与清单说出两种话来。
                .build();

        String conclusion = subagent.prompt(task)
                .toolContext(subagentToolContext(scope, principal))
                .advisors(spec -> spec
                        .param(ContextKeys.PRINCIPAL, principal)
                        // 工具循环按这个键取会话 id，缺了不是"退化成全塞"而是直接抛。
                        // 父那边由 ChatClientAgentPort 塞；子这边没有 AgentPort，只能自己补。
                        // 键用框架的 ChatMemory 常量，与父那边用的是同一个字符串。
                        .param(ChatMemory.CONVERSATION_ID, scope.sessionKey()))
                .call()
                .content();
        return (conclusion == null || conclusion.isBlank()) ? EMPTY_CONCLUSION : conclusion;
    }

    /**
     * 交给子链工具的那份上下文：身份 + 会话 + 这一轮的编号与归属。
     *
     * <p>不加身份那一步时，子链上的工具<b>拿不到身份</b>：它们只从 {@code ToolContext} 里取，而这里
     * 原来只放了 Advisor 参数。后果不是报错，是那些工具按"没有身份"拒绝（委派里的再委派、
     * exec 要审批时找人批，都读这个键）。
     *
     * <p>编号与归属是票 08 补的：工具的审计事件从这两格取 {@code runId} 与 {@code agentId}。
     * 缺 {@code runId} 的后果不是"记成父那一轮"，而是**账本直接跳过**（没有 run 的事件不属于任何一次
     * 运行，写完也查不回来）——这正是子链的调用以前一行审计都没有的原因。
     *
     * <p>会话键与 Advisor 那一侧同一个值：两处各算一遍的话，审批行上的来处与工具读到的会话
     * 迟早会差一段，而差的那一段表现是"批准之后回到的是一段空对话"。
     *
     * @param scope 子这一轮的作用域
     * @param principal 父这一轮的身份
     * @return 工具上下文
     */
    private static java.util.Map<String, Object> subagentToolContext(AuditEvent.Scope scope,
                                                                     Principal principal) {
        java.util.Map<String, Object> context = new java.util.HashMap<>();
        context.put(ContextKeys.PRINCIPAL, principal);
        context.put(ChatMemory.CONVERSATION_ID, scope.sessionKey());
        context.put(ContextKeys.RUN_ID, scope.runId());
        context.put(ContextKeys.AGENT_ID, scope.agentId());
        return context;
    }
}
