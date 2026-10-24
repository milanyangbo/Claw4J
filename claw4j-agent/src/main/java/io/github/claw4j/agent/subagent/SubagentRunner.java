package io.github.claw4j.agent.subagent;

import io.github.claw4j.agent.ToolCallBudget;
import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.domain.principal.Principal;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 委派的执行器：为一次委派开一个<b>独立上下文窗口</b>，跑完只把结论文本交回父 Agent。
 *
 * <p><b>做深的是上下文隔离，不是"起了第二个 Agent"</b>（ADR-0008）：后者配一个新
 * {@code ChatClient} 就有，前者才有断言可写。隔离落在这三处，每处都可测：
 * <ul>
 *   <li><b>子窗口是新建的</b>：这里 {@code ChatClient.builder(...)} 每次委派都从头建一个，
 *       不带父的任何一条消息。父那一轮的对话历史对子不存在，子也看不见父问了什么——
 *       它只拿到 {@link #delegate} 的 {@code task} 那一段。
 *   <li><b>回传的只有 {@code content()}</b>：子 Agent 中间调了几次工具、每次拿到什么，
 *       全留在子窗口的消息里，父那边只有"这次委派的结果"这一个字符串。
 *       这是"只回传结论"在代码里的样子，也是 {@code SubagentRunnerTest} 那条断言盯的地方。
 *   <li><b>子链上不挂记忆与脱敏</b>：记忆会把子的中间过程写进会话，等于把刚隔离掉的东西
 *       从另一个门漏回去；脱敏在父链出站前还会做一次，子在中间打码只会让它认不出自己查到的值。
 * </ul>
 *
 * <p><b>委派不提权</b>：子的工具调用照样过 {@link AuthorizationAdvisor}，
 * 用的就是父那个 {@link Principal}——身份由委派时的入站请求给，不由 Subagent 定义给。
 * 于是"父看不到的表，派个 Subagent 就看得到"这条路从设计上不存在
 * （{@code SubagentRunnerTest} 里 guest 那条盯着它）。
 *
 * <p><b>子也吃同一份调用预算</b>（3 / 6，见 {@link ToolCallBudget}）：
 * manager 显式塞进 {@code ToolCallingAdvisor}，与父那一侧同一个构造入口。
 * 委派不能变成绕过预算的口子——否则"一个坏掉的脚本在子里循环"能一直烧下去，
 * 而父那一侧的预算看起来还一点没动。
 *
 * <p><b>工具集在这里才取，而且是 {@code Supplier}</b>：装配期取会撞上循环依赖——
 * 本 Runner 是 {@code Task} 工具回调的依赖，而 {@code Task} 自己又是一个
 * {@code ToolCallback} bean，{@code ObjectProvider} 在装配期展开就把自己也卷进去了。
 * 换成 {@code Supplier} 之后，展开发生在委派那一刻，容器早已就绪。
 * 代价要记账：白名单点名了一个没装上的工具时，失败发生在<b>首次委派</b>而不是启动时，
 * 失败姿势仍然是拒绝并点名缺哪个工具（见 {@link SubagentDefinition#selectTools}）。
 */
public final class SubagentRunner {

    private static final Logger log = LoggerFactory.getLogger(SubagentRunner.class);

    /** 子 Agent 没能给出任何结论时的回传文本：父至少要知道"派出去但没拿到东西"。 */
    private static final String EMPTY_CONCLUSION = "（这个 Subagent 没有给出结论）";

    private final ChatModel model;

    private final ObservationRegistry observations;

    private final Supplier<List<ToolCallback>> availableTools;

    private final Advisor authorizationAdvisor;

    public SubagentRunner(ChatModel model,
                          ObservationRegistry observations,
                          Supplier<List<ToolCallback>> availableTools,
                          Advisor authorizationAdvisor) {
        this.model = Objects.requireNonNull(model, "model");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.availableTools = Objects.requireNonNull(availableTools, "availableTools");
        this.authorizationAdvisor = Objects.requireNonNull(authorizationAdvisor, "authorizationAdvisor");
    }

    /**
     * 派一个 Subagent 去做这件事，只把结论带回来。
     *
     * @param task 交给子 Agent 的完整任务描述。它看不到父的对话，要什么就得在这里写清。
     */
    public String delegate(SubagentDefinition definition, String task, Principal principal) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(principal, "principal");

        List<ToolCallback> tools = definition.selectTools(availableTools.get());
        log.debug("委派 task 给 Subagent {}：它这趟能用的工具是 {}",
                definition.name(),
                tools.stream().map(callback -> callback.getToolDefinition().name()).toList());

        ChatClient subagent = ChatClient.builder(
                        model,
                        observations,
                        null,
                        null,
                        ToolCallingAdvisor.builder()
                                .toolCallingManager(ToolCallBudget.toolCallingManager(observations)))
                .defaultSystem(definition.systemPrompt())
                .defaultToolCallbacks(tools)
                // 只挂鉴权：记忆与脱敏都不进子链（理由见类注释）。
                .defaultAdvisors(authorizationAdvisor)
                .build();

        String conclusion = subagent.prompt(task)
                .advisors(spec -> spec.param(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, principal))
                .call()
                .content();
        return (conclusion == null || conclusion.isBlank()) ? EMPTY_CONCLUSION : conclusion;
    }
}
