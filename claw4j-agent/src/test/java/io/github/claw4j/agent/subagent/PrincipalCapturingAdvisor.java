package io.github.claw4j.agent.subagent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.domain.principal.Principal;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把子链上看到的身份记下来的探针。
 *
 * <p>"委派不提权"有两个方向要盯：一是子 Agent 用不了父没有的权限
 * （{@code SubagentRunnerTest} 里 guest 那条），二是<b>它用的就是父那个身份本身</b>
 * ——不是一份被抬高过的副本，也不是一个新的匿名身份。后者靠这个探针：
 * 把它挂在子链上，断言它记到的 {@code Principal} 与委派时传进去的是同一个对象。
 *
 * <p>它不做任何拦截，只观察然后放行——所以用例里要验拦截时得挂真的
 * {@link AuthorizationAdvisor}，两者不互相替代。
 */
public final class PrincipalCapturingAdvisor implements CallAdvisor, StreamAdvisor {

    private final List<Principal> seen = new ArrayList<>();

    @Override
    public String getName() {
        return "principalCapturingAdvisor";
    }

    @Override
    public int getOrder() {
        // 与鉴权同一个位置：工具循环内部，于是每一次工具迭代都能看到身份
        return AuthorizationAdvisor.ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        capture(request.context());
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        capture(request.context());
        return chain.nextStream(request);
    }

    /** 子链上每一轮看到的身份，按轮次。 */
    public List<Principal> principals() {
        return List.copyOf(seen);
    }

    private void capture(Map<String, Object> context) {
        if (context == null) {
            seen.add(null);
            return;
        }
        Object value = context.get(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY);
        seen.add(value instanceof Principal principal ? principal : null);
    }
}
