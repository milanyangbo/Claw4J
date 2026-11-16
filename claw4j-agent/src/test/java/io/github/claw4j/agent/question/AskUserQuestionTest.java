package io.github.claw4j.agent.question;

import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.question.QuestionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交互提问这一层：谁答、怎么答、答不上来怎么办。
 *
 * <p>不起 Spring：{@link PendingQuestions} 只认 {@link OutboundDelivery} 与 {@link Principal}
 * 两个东西，用一张 map 记"推出去了什么"就能断言到"问题真的到了那段对话"。
 */
@DisplayName("交互提问：运行内等待，答案从对话里回来")
class AskUserQuestionTest {

    /** 一个问题、两个选项，第二个是推荐项。 */
    private static final String QUESTION = "部署到哪个环境？";

    /** 推出去的东西：会话地址 → 那段话。 */
    private final Map<String, String> pushed = new ConcurrentHashMap<>();

    private final OutboundDelivery feishu = new OutboundDelivery() {

        @Override
        public String channel() {
            return "feishu";
        }

        @Override
        public void send(String channelRef, String text) {
            pushed.put(channelRef, text);
        }
    };

    private final ExecutorService waiting = Executors.newCachedThreadPool();

    @Test
    @DisplayName("回数字 / 选项名 / 自由文本都能答对，答案按题目原文分组")
    void plainTextAnswersResolvePendingQuestion() throws Exception {
        PendingQuestions gate = gate();
        Future<QuestionGate.Outcome> outcome = askInBackground(gate, singleChoiceAsk(Duration.ofSeconds(5)));
        awaitPushed("chat-1");

        assertThat(gate.answer("feishu", "chat-1", asker(), "2"))
                .as("这句话该被当成答案消费掉")
                .isTrue();

        assertThat(outcome.get(3, TimeUnit.SECONDS).answers())
                .as("数字 2 要换成选项名：模型拿到的该是人在选的那个东西，不是一个序号")
                .containsEntry(QUESTION, List.of("预发"));

        assertThat(pushed.get("chat-1"))
                .as("问题要真的推到那段对话里，并且带上可选的选项")
                .contains(QUESTION)
                .contains("生产")
                .contains("预发");
    }

    @Test
    @DisplayName("答复的人与提问的人权限不一致时不当答案：那句话走正常一轮，问题等到超时")
    void answererMustMatchTheAsker() throws Exception {
        PendingQuestions gate = gate();
        Future<QuestionGate.Outcome> outcome = askInBackground(gate, singleChoiceAsk(Duration.ofMillis(400)));
        awaitPushed("chat-1");

        assertThat(gate.answer("feishu", "chat-1", other(), "1"))
                .as("别人一句话就能替提问人做决定，等于绕过他手里那个选择")
                .isFalse();

        assertThat(outcome.get(3, TimeUnit.SECONDS).status())
                .isEqualTo(QuestionGate.Status.NO_ANSWER);
    }

    @Test
    @DisplayName("没人答就按没答收场，登记表里不留残渣")
    void timeoutYieldsNoAnswer() throws Exception {
        PendingQuestions gate = gate();
        Future<QuestionGate.Outcome> outcome = askInBackground(gate, singleChoiceAsk(Duration.ofMillis(200)));

        assertThat(outcome.get(3, TimeUnit.SECONDS).status()).isEqualTo(QuestionGate.Status.NO_ANSWER);
        assertThat(gate.pending("feishu", "chat-1"))
                .as("超时之后还挂着的话，下一句无关的话会被当成迟到的答案")
                .isEmpty();
    }

    @Test
    @DisplayName("没有推送面的通道不等人：立刻说这儿问不了，模型改用普通文字问")
    void surfaceWithoutPushIsUnsupported() {
        PendingQuestions gate = new PendingQuestions(List.of(), Duration.ofSeconds(5), null);

        QuestionGate.Outcome outcome = gate.ask(new QuestionGate.Ask(
                "wecom", "chat-1", asker(), "run-1", singleChoice(), Duration.ofSeconds(5)));

        assertThat(outcome.status())
                .as("既没有 OutboundDelivery 又不是 http：等下去也没人看得见这个问题")
                .isEqualTo(QuestionGate.Status.UNSUPPORTED);
    }

    @Test
    @DisplayName("同一段对话只挂一个问题：第二个立刻按没答返回，不顶掉正在等的那一个")
    void onePendingPerConversation() throws Exception {
        PendingQuestions gate = gate();
        Future<QuestionGate.Outcome> first = askInBackground(gate, singleChoiceAsk(Duration.ofSeconds(5)));
        awaitPushed("chat-1");

        QuestionGate.Outcome second = gate.ask(new QuestionGate.Ask(
                "feishu", "chat-1", asker(), "run-2",
                List.of(question("还要问一句？", "是", "否")), Duration.ofSeconds(5)));

        assertThat(second.status()).isEqualTo(QuestionGate.Status.NO_ANSWER);
        assertThat(gate.answer("feishu", "chat-1", asker(), "2"))
                .as("第二个问题不能把第一个挤掉：挤掉的话，先那一轮永远等不到答案")
                .isTrue();
        assertThat(first.get(3, TimeUnit.SECONDS).status()).isEqualTo(QuestionGate.Status.ANSWERED);
    }

    @Test
    @DisplayName("多题要按行答：行数对上才认，对不上立刻放开那一轮而不是耗到超时")
    void multiQuestionNeedsOneLinePerQuestion() throws Exception {
        PendingQuestions gate = gate();
        List<QuestionGate.Question> questions = List.of(
                question("部署到哪个环境？", "生产", "预发"),
                question("要不要通知业务方？", "要", "不要"));

        Future<QuestionGate.Outcome> ok = askInBackground(gate, new QuestionGate.Ask(
                "feishu", "chat-1", asker(), "run-1", questions, Duration.ofSeconds(5)));
        awaitPushed("chat-1");
        assertThat(gate.answer("feishu", "chat-1", asker(), "2\n1")).isTrue();
        assertThat(ok.get(3, TimeUnit.SECONDS).answers())
                .containsEntry("部署到哪个环境？", List.of("预发"))
                .containsEntry("要不要通知业务方？", List.of("要"));

        pushed.clear();
        Future<QuestionGate.Outcome> bad = askInBackground(gate, new QuestionGate.Ask(
                "feishu", "chat-1", asker(), "run-3", questions, Duration.ofSeconds(5)));
        awaitPushed("chat-1");
        assertThat(gate.answer("feishu", "chat-1", asker(), "两个都行")).isTrue();
        assertThat(bad.get(3, TimeUnit.SECONDS).status())
                .as("拆不开就按没答继续：让模型带着一个没人做过的选择往下走更糟")
                .isEqualTo(QuestionGate.Status.NO_ANSWER);
    }

    private static QuestionGate.Question question(String text, String... labels) {
        List<QuestionGate.Option> options = Arrays.stream(labels)
                .map(label -> new QuestionGate.Option(label, null))
                .toList();
        return new QuestionGate.Question("部署", text, options, false);
    }

    private static List<QuestionGate.Question> singleChoice() {
        return List.of(question(QUESTION, "生产", "预发"));
    }

    private static QuestionGate.Ask singleChoiceAsk(Duration timeout) {
        return new QuestionGate.Ask("feishu", "chat-1", asker(), "run-1", singleChoice(), timeout);
    }

    private Future<QuestionGate.Outcome> askInBackground(PendingQuestions gate, QuestionGate.Ask ask) {
        return waiting.submit(() -> gate.ask(ask));
    }

    private PendingQuestions gate() {
        return new PendingQuestions(List.of(feishu), Duration.ofSeconds(5), null);
    }

    /** 等问题真的推出去，再答：那一轮在线程池里，抢跑会拿到空的登记表。 */
    private void awaitPushed(String chatRef) throws InterruptedException {
        for (int i = 0; i < 100 && !pushed.containsKey(chatRef); i++) {
            Thread.sleep(10);
        }
        assertThat(pushed).as("问题一直没有推到 %s", chatRef).containsKey(chatRef);
    }

    private static Principal asker() {
        return new Principal("u1", "admin", Set.of("*"), Set.of("*"));
    }

    private static Principal other() {
        return new Principal("u2", "read-only", Set.of("*"), Set.of("read"));
    }
}
