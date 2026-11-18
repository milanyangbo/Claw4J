package io.github.claw4j.agent.question;

import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.question.QuestionGate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 交互提问的登记表：进程内一张表，一条会话最多一个问题，谁把自己那句话答上来就放开那一轮。
 *
 * <p><b>为什么是进程内的表而不是一张库表</b>：照着 OpenClaw 的 {@code ask_user} 来——它写明用
 * "existing process-local question lifecycle, <b>not a durable recovery ledger</b>"，并且
 * "A Gateway restart or an aborted/replaced run does not turn an unresolved step into success"。
 * 换成持久账本要多出状态机、清扫、跨实例汇合三样东西，而它们服务的是"重启之后接着等一个答案"，
 * 那件事没人要：等不到就按 {@code no_answer} 继续，比"重启之后忽然收到一个隔夜问题的答复"更清楚。
 *
 * <p><b>为什么按会话一对一</b>：同一段对话里同时挂两个问题，用户的下一句话该算哪一个？
 * OpenClaw 那边是一个面板排着队，我们这里没有面板。后一个直接返回 {@code no_answer}，
 * 模型会带着上一个问题还没答这件事继续——这比"顶掉前一个"好，前一个正有一轮在等它。
 *
 * <p>推给谁：{@link OutboundDelivery} 里那个通道的实现。HTTP 没有推送面（连接是请求-响应的），
 * 它靠客户端轮询 {@link #pending}，所以不算"不支持"——{@code http} 与没有通道名的内部调用都按可轮询处理。
 */
public final class PendingQuestions implements QuestionGate {

    private static final Logger log = LoggerFactory.getLogger(PendingQuestions.class);

    /** 指标前缀。答案等到了、等超了、通道不支持，三种结局各一个数。 */
    public static final String METRIC = "claw4j.question";

    /** 没有推送面但答案拿得到的通道：客户端自己在请求挂着的时候来问。 */
    private static final String HTTP = "http";

    /** 各通道的推送实现。没有实现的通道要靠轮询，或者干脆不支持。 */
    private final List<OutboundDelivery> deliveries;

    /** 一次最多等人多久。钳过的值在 {@code AskProperties} 里。 */
    private final Duration defaultTimeout;

    /** 为 null 表示没装 actuator。 */
    private final MeterRegistry registry;

    /** 会话地址 → 正在等答案的那一条。 */
    private final Map<String, Pending> byChat = new ConcurrentHashMap<>();

    /**
     * 登记表。
     *
     * @param deliveries 各通道的推送实现，为 null 时按空处理
     * @param defaultTimeout 最多等人多久，必填
     * @param registry 指标注册表，为 null 时不记指标
     */
    public PendingQuestions(List<OutboundDelivery> deliveries, Duration defaultTimeout, MeterRegistry registry) {
        this.deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        this.registry = registry;
    }

    @Override
    public Outcome ask(Ask ask) {
        OutboundDelivery delivery = deliveryFor(ask.channel());
        if (delivery == null && !pollable(ask.channel())) {
            // 没有推送面、也拿不到轮询（这个通道没有实现 OutboundDelivery）：照 OpenClaw 对
            // "surfaces without question input" 的处理，让模型改用普通文字问。
            count("unsupported", ask.channel());
            log.info("通道 {} 没有提问面，这个问题不等人：{}", ask.channel(), ask.chatRef());
            return Outcome.unsupported();
        }
        Pending pending = new Pending(ask, ask.creator().userId());
        if (byChat.putIfAbsent(key(ask.channel(), ask.chatRef()), pending) != null) {
            count("busy", ask.channel());
            log.warn("这段对话已经有一个待答问题，这次不再开第二个：channel={} chat={}",
                    ask.channel(), ask.chatRef());
            return Outcome.noAnswer();
        }
        log.info("已登记一个待答问题：channel={} chat={} 问题 {} 个，最多等 {} 秒",
                ask.channel(), ask.chatRef(), ask.questions().size(),
                (ask.timeout() == null ? defaultTimeout : ask.timeout()).toSeconds());
        try {
            if (delivery != null) {
                announce(delivery, ask);
            }
            Outcome outcome = await(pending, ask.timeout() == null ? defaultTimeout : ask.timeout());
            count(outcome.status().name().toLowerCase(java.util.Locale.ROOT), ask.channel());
            return outcome;
        }
        finally {
            byChat.remove(key(ask.channel(), ask.chatRef()), pending);
        }
    }

    @Override
    public boolean answer(String channel, String chatRef, Principal who, String text) {
        Pending pending = byChat.get(key(channel, chatRef));
        if (pending == null) {
            return false;
        }
        if (who == null || !pending.creator.equals(who.userId())) {
            // 不吞掉：这句照常走一轮，提问人手里那个决定不该被别人代答。
            // 判据是"谁问的"（userId），不是权限指纹：同一个人换了配置仍然能答自己的问题，
            // 而换一个人来答，哪怕他的权限一模一样，也不该替他点这个。
            log.warn("答复的人不是提问的人，这句不当答案：channel={} chat={} asker={} answerer={}",
                    channel, chatRef, pending.ask.creator().userId(), who == null ? null : who.userId());
            count("rejected", channel);
            return false;
        }
        Map<String, List<String>> parsed = parse(text, pending.ask.questions());
        if (parsed == null) {
            // 多题但拆不开：立刻放开那一轮，别让人在错误的格式上等到超时。
            log.warn("答复拆不成 {} 个答案，按没答处理：channel={} chat={}", pending.ask.questions().size(),
                    channel, chatRef);
            pending.settle(Outcome.noAnswer());
            return true;
        }
        pending.settle(Outcome.answered(parsed));
        return true;
    }

    @Override
    public List<Ask> pending(String channel, String chatRef) {
        Pending pending = byChat.get(key(channel, chatRef));
        return pending == null ? List.of() : List.of(pending.ask);
    }

    /** 等人。超时与被中断都按"没人答"收场：那一轮该继续，不该一直挂着。 */
    private Outcome await(Pending pending, Duration timeout) {
        try {
            return pending.outcome.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException expired) {
            log.info("等了 {} 秒没人答，按没答处理：channel={} chat={}",
                    timeout.toSeconds(), pending.ask.channel(), pending.ask.chatRef());
            return Outcome.noAnswer();
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.noAnswer();
        }
        catch (ExecutionException broken) {
            log.warn("提问这一路崩了，按没答处理：{}", broken.getCause() == null ? broken.toString()
                    : broken.getCause().toString());
            return Outcome.noAnswer();
        }
    }

    /** 把问题推到那段对话。推失败只记日志：等人等不到答案与推不出去是两件事，前者还能救。 */
    private void announce(OutboundDelivery delivery, Ask ask) {
        try {
            delivery.send(ask.chatRef(), render(ask));
        }
        catch (RuntimeException failure) {
            log.warn("问题没推出去（通道 {} 地址 {}）：这一轮照常等答案，只是人可能看不见它",
                    ask.channel(), ask.chatRef(), failure);
        }
    }

    /**
     * 问题长什么样。纯文本 + 编号：四个 IM 通道都能收，飞书卡片是另一件事。
     *
     * @param ask 这次提问
     * @return 发出去的那段话
     */
    static String render(Ask ask) {
        StringBuilder text = new StringBuilder("我需要你定一下：\n");
        List<Question> questions = ask.questions();
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            if (questions.size() > 1) {
                text.append(i + 1).append(". ");
            }
            if (question.header() != null && !question.header().isBlank()) {
                text.append("【").append(question.header()).append("】");
            }
            text.append(question.text()).append('\n');
            for (int j = 0; j < question.options().size(); j++) {
                Option option = question.options().get(j);
                text.append("  ").append(j + 1).append(") ").append(option.label());
                if (option.description() != null && !option.description().isBlank()) {
                    text.append(" —— ").append(option.description());
                }
                text.append('\n');
            }
            if (question.multiSelect()) {
                text.append("  （可多选，用逗号隔开）\n");
            }
        }
        text.append(questions.size() == 1
                ? "直接回数字、选项名，或者你自己的说法。"
                : "这轮有 " + questions.size() + " 个问题，请按顺序分行回答。");
        return text.toString();
    }

    /**
     * 把一句话拆成"每题一组答案"。
     *
     * <p>单选：整句当一个值；多选：按逗号拆。每题的值再归一化：纯数字按 1 起的序号映射到选项名，
     * 命中选项名（大小写不敏感）用选项原名，其余原样当自由文本——「Other」这一档在库里没有，
     * 用户想说什么就说什么。
     *
     * @param text 用户说的话
     * @param questions 这次的问题
     * @return 按题目原文分组的答案；多题但拆不开时为 {@code null}
     */
    static Map<String, List<String>> parse(String text, List<Question> questions) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (questions.size() == 1) {
            return Map.of(questions.get(0).text(), valuesOf(trimmed, questions.get(0)));
        }
        List<String> parts = splitAnswers(trimmed);
        if (parts.size() != questions.size()) {
            return null;
        }
        Map<String, List<String>> answers = new LinkedHashMap<>();
        for (int i = 0; i < questions.size(); i++) {
            answers.put(questions.get(i).text(), valuesOf(parts.get(i), questions.get(i)));
        }
        return answers;
    }

    private static List<String> valuesOf(String raw, Question question) {
        List<String> pieces = question.multiSelect() ? splitMulti(raw) : List.of(raw.strip());
        List<String> values = new ArrayList<>(pieces.size());
        for (String piece : pieces) {
            values.add(normalize(piece, question));
        }
        return List.copyOf(values);
    }

    private static String normalize(String piece, Question question) {
        String candidate = piece.strip();
        if (candidate.isEmpty()) {
            return candidate;
        }
        if (candidate.matches("\\d{1,2}")) {
            int index = Integer.parseInt(candidate) - 1;
            if (index >= 0 && index < question.options().size()) {
                return question.options().get(index).label();
            }
        }
        for (Option option : question.options()) {
            if (option.label().equalsIgnoreCase(candidate)) {
                return option.label();
            }
        }
        return candidate;
    }

    private static List<String> splitAnswers(String text) {
        List<String> parts = new ArrayList<>();
        for (String line : text.split("\\r?\\n|\\|")) {
            String part = line.strip();
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static List<String> splitMulti(String raw) {
        List<String> pieces = new ArrayList<>();
        for (String piece : raw.split("[,，、]")) {
            String value = piece.strip();
            if (!value.isEmpty()) {
                pieces.add(value);
            }
        }
        return pieces.isEmpty() ? List.of(raw.strip()) : pieces;
    }

    private OutboundDelivery deliveryFor(String channel) {
        if (channel == null) {
            return null;
        }
        return deliveries.stream()
                .filter(delivery -> channel.equals(delivery.channel()))
                .findFirst()
                .orElse(null);
    }

    /** 没有推送面但答案取得到的通道：HTTP 的客户端在请求挂着的时候来轮询。 */
    private static boolean pollable(String channel) {
        return channel == null || HTTP.equals(channel);
    }

    private static String key(String channel, String chatRef) {
        return (channel == null ? "" : channel) + '\u0000' + (chatRef == null ? "" : chatRef);
    }

    private void count(String outcome, String channel) {
        if (registry == null) {
            return;
        }
        Counter.builder(METRIC)
                .tag("outcome", outcome)
                .tag("channel", channel == null ? "none" : channel)
                .register(registry)
                .increment();
    }

    /** 一条正在等答案的问题。 */
    private static final class Pending {

        private final Ask ask;

        /** 提问人的 {@code userId}：答复的必须是同一个人（OpenClaw 同一条）。 */
        private final String creator;

        private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();

        private Pending(Ask ask, String creator) {
            this.ask = ask;
            this.creator = creator;
        }

        private void settle(Outcome settled) {
            outcome.complete(settled);
        }
    }

    /** 只用于日志与断言：这段对话有没有人在等答案。 */
    public Optional<Ask> waiting(String channel, String chatRef) {
        return pending(channel, chatRef).stream().findFirst();
    }
}
