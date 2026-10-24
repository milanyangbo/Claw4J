package io.github.claw4j.agent.advisor;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 鉴权 Advisor：一次工具调用能不能发生由 Principal 决定，不由模型决定（ADR-0007）。
 *
 * <p>摆位是这个 Advisor 的全部要点。{@code ToolCallingAdvisor} 把工具循环实现成了
 * "自己递归调用链上后续的 Advisor"，所以排在它**之后**的 Advisor 会在
 * <b>每一次工具迭代</b>上都被走一遍；排在它之前的只在整通对话里走一次。
 * 链是按 order 升序排的，于是"在循环内部"= order 大于 {@link ToolCallingAdvisor#DEFAULT_ORDER}。
 *
 * <p>判定发生在模型返回之后、工具执行之前：这一轮模型说"我要调 querySales"，
 * 本 Advisor 先看 Principal 有没有这个权限，没有就把这一轮换成一个明确的拒绝，
 * 并且**不把工具调用请求往下传**——{@code ToolCallingAdvisor} 因此看不到工具调用，
 * 工具一次都不会被执行。注意这与"让工具跑完再过滤结果"有本质区别：
 * 后者在执行那一刻越权已经发生了。
 *
 * <p>为什么不直接用 {@code BaseAdvisor} 的默认流式实现：它只在
 * {@code AdvisorUtils.onFinishReason()} 命中的那一块上调 {@code after()}，
 * 而真实模型把工具调用分成很多块发（名字在第一块、参数是后面几块），
 * 带 finish reason 的收尾块里通常**已经没有工具调用**了。所以在流式上只能逐块看，
 * 看到越权就当场掐断上游并把这一轮换成拒绝。
 */
public class AuthorizationAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * Principal 在 Advisor 上下文里的键。由 {@code ChatClientAgentPort} 放入，本 Advisor 取出。
     *
     * <p>取值来自 domain 的 {@link ContextKeys#PRINCIPAL}：工具回调里读的是同一个键
     * （委派与脚本执行都发生在工具内部，那里没有 Advisor 上下文），两边各写一份常量的话，
     * 漂移的表现是"工具侧读到 null、一律按没身份拒绝"。
     */
    public static final String PRINCIPAL_CONTEXT_KEY = ContextKeys.PRINCIPAL;

    /** 拒绝话术的统一前缀。测试靠它区分"明确的拒绝"与"查出来是空的"。 */
    public static final String DENIAL_PREFIX = "没有权限";

    /**
     * 排在工具循环内部：比 {@link ToolCallingAdvisor#DEFAULT_ORDER} 大，
     * 于是每次迭代都过一遍；比模型调用 Advisor（{@code LOWEST_PRECEDENCE}）小，
     * 于是还有机会在工具执行之前拦下来。
     */
    public static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 100;

    private final List<ToolPermissionRegistry> registries;

    public AuthorizationAdvisor(List<ToolPermissionRegistry> registries) {
        this.registries = List.copyOf(Objects.requireNonNull(registries, "registries"));
    }

    @Override
    public String getName() {
        return "authorizationAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(chain, "chain");
        ChatClientResponse response = chain.nextCall(request);
        return denyIfNecessary(request.context(), response);
    }

    /**
     * 流式路径：逐块看，看到越权的工具调用就发一条拒绝并掐断上游。
     *
     * <p>不能攒到最后再判断：攒起来的话，越权那一轮的内容已经吐出去了，
     * 而 {@code ToolCallingAdvisor} 拿的是聚合后的响应，它照样会去执行工具。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(chain, "chain");
        Map<String, Object> context = request.context();
        return chain.nextStream(request).handle((chunk, sink) -> {
            Optional<String> denial = denialFor(context, requestedTools(chunk.chatResponse()));
            if (denial.isPresent()) {
                sink.next(replacedWith(chunk, denial.get()));
                sink.complete();
                return;
            }
            sink.next(chunk);
        });
    }

    /** 同步路径：模型这一轮要调的工具逐个过一遍，有一个越权就把整轮换成拒绝。 */
    private ChatClientResponse denyIfNecessary(Map<String, Object> context, ChatClientResponse response) {
        Optional<String> denial = denialFor(context, requestedTools(response.chatResponse()));
        return denial.<ChatClientResponse>map(text -> replacedWith(response, text)).orElse(response);
    }

    private Optional<String> denialFor(Map<String, Object> context, List<String> toolNames) {
        if (toolNames.isEmpty()) {
            return Optional.empty();
        }
        Principal principal = principalOf(context);
        if (principal == null) {
            return Optional.of(DENIAL_PREFIX + "：本次请求没有携带身份，工具调用一律不放行。");
        }
        for (String toolName : toolNames) {
            Optional<ToolPermission> permission = permissionFor(toolName);
            if (permission.isEmpty()) {
                return Optional.of(DENIAL_PREFIX + "：工具 " + toolName
                        + " 未登记权限（没人评估过它会碰到什么），按越权处理。");
            }
            ToolPermission required = permission.get();
            if (!principal.canUse(required)) {
                return Optional.of(denialText(principal, toolName, required));
            }
        }
        return Optional.empty();
    }

    /**
     * 拒绝话术要**说清是哪一条不满足**：动作还是表。
     * 只说"没有权限"的话，运营同学没法判断是该给动作还是该给表，最后往往直接给个 "*" 了事。
     */
    private static String denialText(Principal principal, String toolName, ToolPermission required) {
        if (!principal.canPerform(required.action())) {
            return DENIAL_PREFIX + "：角色 " + principal.role()
                    + " 不允许执行 " + required.action() + " 动作（工具 " + toolName + "）。";
        }
        List<String> forbidden = required.tables()
                .stream()
                .filter(table -> !principal.canReadTable(table))
                .sorted()
                .toList();
        return DENIAL_PREFIX + "：角色 " + principal.role()
                + " 不允许访问表 " + String.join("、", forbidden) + "（工具 " + toolName + "）。";
    }

    private Optional<ToolPermission> permissionFor(String toolName) {
        for (ToolPermissionRegistry registry : registries) {
            Optional<ToolPermission> permission = registry.permissionFor(toolName);
            if (permission.isPresent()) {
                return permission;
            }
        }
        return Optional.empty();
    }

    private static Principal principalOf(Map<String, Object> context) {
        Object value = context == null ? null : context.get(PRINCIPAL_CONTEXT_KEY);
        return value instanceof Principal principal ? principal : null;
    }

    /**
     * 这一轮模型要调的工具名。名字为空的分块（流式下参数是一点一点送的）直接跳过——
     * 那种分块没法判定，能判定的第一块一定带着名字。
     */
    private static List<String> requestedTools(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResults() == null) {
            return List.of();
        }
        return chatResponse.getResults()
                .stream()
                .map(Generation::getOutput)
                .filter(Objects::nonNull)
                .map(AssistantMessage::getToolCalls)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .map(AssistantMessage.ToolCall::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 把这一轮换成一句明确的拒绝。必须连工具调用一起换掉：
     * 只换正文的话 {@code ToolCallingAdvisor} 看到的仍然是一个"要调工具"的响应，
     * 工具照跑，客户端拿到的是"拒绝 + 真数据"这种自相矛盾的结果。
     */
    private static ChatClientResponse replacedWith(ChatClientResponse response, String text) {
        ChatResponse.Builder builder = ChatResponse.builder();
        if (response.chatResponse() != null) {
            builder.from(response.chatResponse());
        }
        ChatResponse denied = builder.generations(List.of(new Generation(new AssistantMessage(text)))).build();
        return response.mutate().chatResponse(denied).build();
    }
}
