package io.github.claw4j.agent.subagent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 子 Agent 那一环的桩：按脚本决定"模型这一轮要调什么工具 / 说什么"。
 *
 * <p><b>使用边界与 {@code FixedChatModel} 一致</b>（那个是"不管问什么都同一句"，
 * 这个是"第几轮说哪一句"）：验的是编排器这一层的外部行为——
 * 子窗口里有哪些工具、回传给父的是不是只有结论、超预算时会不会停下来——
 * 这些都不依赖模型真的会什么。凡是要验"模型会不会派、会不会算"的用例一律走
 * {@code RealModelSupport}（见 {@code SubagentEndToEndTest}）。
 *
 * <p>脚本用完之后一直重复最后一步：预算那条用例要的就是"它一直要求调同一个工具"，
 * 而"脚本走完了该给什么"不是那些用例要验的东西。
 */
public final class SubagentModelStub implements ChatModel {

    private final List<ChatResponse> script;

    private final List<Prompt> prompts = new ArrayList<>();

    private SubagentModelStub(List<ChatResponse> script) {
        this.script = List.copyOf(Objects.requireNonNull(script, "script"));
    }

    /** 每一轮都要求调这个工具。预算那条用例靠它把上限逼出来。 */
    public static SubagentModelStub alwaysRequesting(String toolName) {
        return new SubagentModelStub(List.of(requesting(toolName)));
    }

    /** 第一轮要求调这个工具，之后给出结论。 */
    public static SubagentModelStub requestingThen(String toolName, String conclusion) {
        return new SubagentModelStub(List.of(requesting(toolName), text(conclusion)));
    }

    /** 第一轮就给结论，不调任何工具。 */
    public static SubagentModelStub answering(String conclusion) {
        return new SubagentModelStub(List.of(text(conclusion)));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        return step();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        return Flux.just(step());
    }

    /** 不能返回 null：{@code ChatClient} 装配请求时会直接 {@code getOptions().mutate()}。 */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    /** 收到了几轮请求。断言"委派真的跑了子那一趟"靠它。 */
    public int calls() {
        return prompts.size();
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    private ChatResponse step() {
        int index = Math.min(prompts.size() - 1, script.size() - 1);
        return script.get(Math.max(index, 0));
    }

    private static ChatResponse requesting(String toolName) {
        var call = new AssistantMessage.ToolCall("call-" + toolName, "function", toolName, "{}");
        return responseOf(AssistantMessage.builder().content("").toolCalls(List.of(call)).build());
    }

    private static ChatResponse text(String content) {
        return responseOf(AssistantMessage.builder().content(content).build());
    }

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }
}
