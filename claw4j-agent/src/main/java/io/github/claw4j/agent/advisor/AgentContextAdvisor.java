package io.github.claw4j.agent.advisor;

import io.github.claw4j.agent.advisor.AdvisorOrder;
import io.github.claw4j.persona.GlossarySection;
import io.github.claw4j.persona.PromptBudget;
import io.github.claw4j.persona.Persona;
import io.github.claw4j.persona.PromptComposition;
import io.github.claw4j.persona.PromptParts;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(AgentContextAdvisor.class);

    /** 超限只报一次：每轮都刷一条 WARN，真正需要看的那条会被淹掉（票 03）。 */
    private final java.util.concurrent.atomic.AtomicBoolean budgetWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

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

    /**
     * 拼进两段：稳定段（人设 + 口径）在前，每轮段（现在的时间）在后。
     *
     * <p>分成两条 system 消息而不是拼成一条：供应商的前缀缓存按"从头逐字节相同"命中，稳定段在前
     * 且跨天不变，日切只影响后面那条。合成一条的话，跨天之后缓存键全换（票 03）。
     *
     * <p>两段都过预算：超长时先截后面那条，稳定段是人设与口径，砍它等于换了个人。
     *
     * @param request 进来的请求
     * @return 加上提示词的请求
     */
    private ChatClientRequest withContext(ChatClientRequest request) {
        PromptParts parts = PromptComposition.composeParts(persona, glossary, ZonedDateTime.now(clock));
        PromptBudget.Result fitted = PromptBudget.defaults().fit(parts);
        if (fitted.truncated() && budgetWarned.compareAndSet(false, true)) {
            log.warn("提示词超预算已截断：{} → {} 字符（上限 {}），稳定段 {} 字符",
                    fitted.original(), fitted.fitted(), PromptBudget.DEFAULT_TOTAL_MAX_CHARS,
                    fitted.parts().stable().length());
        }
        Prompt prompt = request.prompt();
        List<Message> instructions = prompt.getInstructions();

        List<Message> replaced = new ArrayList<>(instructions.size() + 2);
        replaced.add(new SystemMessage(fitted.parts().stable()));
        replaced.add(new SystemMessage(fitted.parts().variable()));
        for (Message message : instructions) {
            if (!(message instanceof SystemMessage)) {
                replaced.add(message);
            }
        }
        return request.mutate().prompt(new Prompt(replaced, prompt.getOptions())).build();
    }


    @Override
    public int getOrder() {
        return AdvisorOrder.AGENT_CONTEXT;
    }

    @Override
    public String getName() {
        return "AgentContextAdvisor";
    }
}
