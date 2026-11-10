package io.github.claw4j.agent.persona;

import io.github.claw4j.agent.PermissionFingerprint;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.persona.GlossarySection;
import io.github.claw4j.persona.Persona;
import io.github.claw4j.persona.PromptComposition;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 每轮把系统提示词拼好放进去：人设、口径、现在的时间。
 *
 * <p>做成 Advisor 而不是装配期的 {@code defaultSystem}：{@code defaultSystem} 的内容在装配
 * 那一刻就定死了，而「现在几点」必须每轮重算。进程跑一周之后，装配期算出的今天还是启动那天，
 * 而这类错误所有「在同一瞬间比对」的用例都发现不了，表现是模型把上周算到一个月以前去。
 *
 * <p>顺带解决了第二件事：提示词只有一个出口。人设、口径、时间三样在同一次拼装里，
 * {@link PromptComposition} 是纯函数，可以单独钉住，口径不再散落在各份 Skill 与子 Agent
 * 的正文里。
 *
 * <p>顺序必须排在缓存之前（{@link #ORDER}）：框架的语义缓存把
 * {@code SHA-256(整份系统提示词)} 当上下文键。这条要是在它后面跑，缓存哈希的是一份还没拼上
 * 数据范围与时间的提示词，权限与时间窗都不进键，隔离与过期判定同时失效。所以这里不只是
 * 「后面的 Advisor 该看到最终提示词」，是缓存的正确性依赖这个顺序。
 *
 * <p>只替换第一条 {@link SystemMessage}，没有就前插：别的 Advisor 也可能往上下文里放 system
 * （比如记忆压缩的摘要）。整份清掉会让那些内容消失，而提示词短了一截通常要等到某天有人问
 * 「它怎么忘了前面说过的规矩」才发现。
 */
public class AgentContextAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * 最前，但在入站拦截之后。
     *
     * <p>拦截器（{@code GuardAutoConfiguration}，{@code Ordered.HIGHEST_PRECEDENCE}）命中时
     * 直接短路返回，根本不该拼提示词；它之后才是这条。
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    private final Persona persona;
    private final List<GlossarySection> glossary;
    private final Clock clock;

    /**
     * 构造。
     *
     * @param persona 这份部署的人设
     * @param glossary 要拼进提示词的口径段落
     * @param clock 必须带时区（{@code Clock.system(zone)}）。传 {@code Clock.systemUTC()} 也
     *     能用，但那正是这里要防的事：容器里的默认时区通常是 UTC，晚上八点之后今天在模型
     *     那里已经是明天
     */
    public AgentContextAdvisor(Persona persona, List<GlossarySection> glossary, Clock clock) {
        this.persona = Objects.requireNonNull(persona, "persona");
        this.glossary = List.copyOf(Objects.requireNonNull(glossary, "glossary"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 这次生效的人设。启动日志与用例都要能回答「用的是哪一个」。
     *
     * @return 这份部署的人设
     */
    public Persona persona() {
        return persona;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(withContext(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(withContext(request));
    }

    private ChatClientRequest withContext(ChatClientRequest request) {
        Principal who = principalOf(request);
        // 没有 Principal 时不写这一段：无身份的一轮不该与任何人的缓存键相同。
        String dataScope = who == null ? null : PermissionFingerprint.of(who);
        String composed = PromptComposition.compose(persona, glossary,
                ZonedDateTime.now(clock), dataScope);
        Prompt prompt = request.prompt();
        List<Message> instructions = prompt.getInstructions();

        List<Message> replaced = new ArrayList<>(instructions.size() + 1);
        replaced.add(new SystemMessage(composed));
        for (Message message : instructions) {
            if (!(message instanceof SystemMessage)) {
                replaced.add(message);
            }
        }
        return request.mutate().prompt(new Prompt(replaced, prompt.getOptions())).build();
    }

    /**
     * 调用方把身份放在 {@code ContextKeys.PRINCIPAL} 里（与鉴权、缓存同一处）。
     *
     * <p>取不到就是没有，不拿默认权限兜底。兜底的代价是匿名调用与某个真实角色共用同一个缓存键，
     * 而那条路上没有任何东西会报错。
     *
     * @param request 这一轮的请求
     * @return 身份；请求上下文里没有时为 null
     */
    private static Principal principalOf(ChatClientRequest request) {
        Object value = request.context() == null ? null : request.context().get(ContextKeys.PRINCIPAL);
        return value instanceof Principal principal ? principal : null;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public String getName() {
        return "AgentContextAdvisor";
    }
}
