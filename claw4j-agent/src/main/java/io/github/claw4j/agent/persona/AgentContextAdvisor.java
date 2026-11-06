package io.github.claw4j.agent.persona;

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
 * 每轮把系统提示词拼好放进去：人设 + 口径 + 现在的时间。
 *
 * <p><b>为什么是 Advisor 而不是装配期的 {@code defaultSystem}</b>：{@code defaultSystem}
 * 的内容在装配那一刻就定死了，而"现在几点"必须每轮重算。进程跑一周之后，
 * 装配期算出的"今天"还是启动那天——这类错误所有"在同一瞬间比对"的用例都发现不了，
 * 表现是模型把"上周"算到一个月以前去。
 *
 * <p>顺带解决了第二件事：提示词只有一个出口。人设、口径、时间三样在同一次拼装里，
 * {@link PromptComposition} 是纯函数，可以单独钉住——口径不再散落在各份 Skill 与
 * 子 Agent 的正文里。
 *
 * <p><b>顺序放在最前（{@link #ORDER}）</b>：后面的 Advisor 看到的应当是最终提示词。
 * 放在鉴权或缓存之后的话，缓存算 key 时看到的是半份提示词——现在缓存只取最后一条用户消息，
 * 所以没踩上，但那是缓存的实现细节，不该成为这里顺序的依据。
 *
 * <p><b>只替换第一条 {@link SystemMessage}</b>，没有就前插：别的 Advisor 也可能往上下文里
 * 放 system（比如记忆压缩的摘要）。整份清掉会让那些内容消失，
 * 而"提示词短了一截"通常要等到某天有人问"它怎么忘了前面说过的规矩"才发现。
 */
public class AgentContextAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * 最前，但在入站拦截之后。
     *
     * <p>拦截器（{@code GuardAutoConfiguration}，{@code Ordered.HIGHEST_PRECEDENCE}）
     * 命中时直接短路返回、根本不该拼提示词；它之后才是这条。
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    private final Persona persona;
    private final List<GlossarySection> glossary;
    private final Clock clock;

    /**
     * @param clock 必须带时区（{@code Clock.system(zone)}）。传 {@code Clock.systemUTC()} 也"能用"，
     *              但那正是这里要防的事：容器里的默认时区通常是 UTC，晚上八点之后
     *              "今天"在模型那里已经是明天
     */
    public AgentContextAdvisor(Persona persona, List<GlossarySection> glossary, Clock clock) {
        this.persona = Objects.requireNonNull(persona, "persona");
        this.glossary = List.copyOf(Objects.requireNonNull(glossary, "glossary"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 这次生效的人设，启动日志与用例都要回答"用的是哪一个"。 */
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
        String composed = PromptComposition.compose(persona, glossary, ZonedDateTime.now(clock));
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

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public String getName() {
        return "AgentContextAdvisor";
    }
}
