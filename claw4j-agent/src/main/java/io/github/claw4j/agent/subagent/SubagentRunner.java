package io.github.claw4j.agent.subagent;

import io.github.claw4j.agent.ToolCallBudget;
import io.github.claw4j.agent.ToolSearch;
import io.github.claw4j.agent.ToolSearchProperties;
import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.domain.principal.Principal;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.toolsearch.ToolIndex;

import java.util.List;
import java.util.Objects;
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
 * <p>委派不提权：子的工具调用照样过 {@link AuthorizationAdvisor}，用的就是父那个
 * {@link Principal}——身份由委派时的入站请求给，不由 Subagent 定义给。父看不到的表，
 * 派个 Subagent 去看这条路从设计上不存在。
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
public final class SubagentRunner {

    private static final Logger log = LoggerFactory.getLogger(SubagentRunner.class);

    /** 子 Agent 没能给出任何结论时的回传文本：父至少要知道派出去但没拿到东西。 */
    private static final String EMPTY_CONCLUSION = "（这个 Subagent 没有给出结论）";

    private final ChatModel model;

    private final ObservationRegistry observations;

    private final Supplier<List<ToolCallback>> availableTools;

    private final Advisor authorizationAdvisor;

    /** 工具检索索引：与父那条链共用同一个 bean，桶按会话 id 分开（见类注释）。 */
    private final ToolIndex toolIndex;

    private final ToolSearchProperties toolSearch;

    /** 一轮的工具调用熔断（与父同一份配置）：委派不该变成绕过它的口子。 */
    private final int toolCallLimit;

    public SubagentRunner(ChatModel model,
                          ObservationRegistry observations,
                          Supplier<List<ToolCallback>> availableTools,
                          Advisor authorizationAdvisor,
                          ToolIndex toolIndex,
                          ToolSearchProperties toolSearch,
                          int toolCallLimit) {
        this.model = Objects.requireNonNull(model, "model");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.availableTools = Objects.requireNonNull(availableTools, "availableTools");
        this.authorizationAdvisor = Objects.requireNonNull(authorizationAdvisor, "authorizationAdvisor");
        this.toolIndex = Objects.requireNonNull(toolIndex, "toolIndex");
        this.toolSearch = Objects.requireNonNull(toolSearch, "toolSearch");
        this.toolCallLimit = toolCallLimit;
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

        List<ToolCallback> tools = definition.selectTools(availableTools.get());
        log.debug("委派 task 给 Subagent {}：它这趟能用的工具是 {}",
                definition.name(),
                tools.stream().map(callback -> callback.getToolDefinition().name()).toList());

        ChatClient subagent = ChatClient.builder(model, observations, null, null,
                        ToolSearch.toolLoop(toolSearch, toolIndex, observations, toolCallLimit))
                .defaultSystem(definition.systemPrompt())
                .defaultToolCallbacks(tools)
                // 只挂鉴权：记忆与脱敏都不进子链（理由见类注释）。
                .defaultAdvisors(authorizationAdvisor)
                .build();

        String conclusion = subagent.prompt(task)
                .advisors(spec -> spec
                        .param(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, principal)
                        // 工具循环按这个键取会话 id，缺了不是"退化成全塞"而是直接抛。
                        // 父那边由 ChatClientAgentPort 塞；子这边没有 AgentPort，只能自己补。
                        // 键用框架的 ChatMemory 常量，与父那边用的是同一个字符串。
                        .param(ChatMemory.CONVERSATION_ID, "subagent:" + definition.name()))
                .call()
                .content();
        return (conclusion == null || conclusion.isBlank()) ? EMPTY_CONCLUSION : conclusion;
    }
}
