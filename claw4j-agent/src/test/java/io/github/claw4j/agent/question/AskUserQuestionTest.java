package io.github.claw4j.agent.question;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.question.QuestionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.time.Duration;
import java.util.ArrayList;
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
    @DisplayName("回数字 / 选项名 / 自由文本都能答对，答案按问题 id 分组")
    void plainTextAnswersResolvePendingQuestion() throws Exception {
        PendingQuestions gate = gate();
        Future<QuestionGate.Outcome> outcome = askInBackground(gate, singleChoiceAsk(Duration.ofSeconds(5)));
        awaitPushed("chat-1");

        assertThat(gate.answer("feishu", "chat-1", asker(), "2"))
                .as("这句话该被当成答案消费掉")
                .isTrue();

        assertThat(outcome.get(3, TimeUnit.SECONDS).answers())
                .as("数字 2 要换成选项名：模型拿到的该是人在选的那个东西，不是一个序号")
                .containsEntry("deploy_target", List.of("预发"));

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
                question("deploy_env", "部署到哪个环境？", "生产", "预发"),
                question("notify", "要不要通知业务方？", "要", "不要"));

        Future<QuestionGate.Outcome> ok = askInBackground(gate, new QuestionGate.Ask(
                "feishu", "chat-1", asker(), "run-1", questions, Duration.ofSeconds(5)));
        awaitPushed("chat-1");
        assertThat(gate.answer("feishu", "chat-1", asker(), "2\n1")).isTrue();
        assertThat(ok.get(3, TimeUnit.SECONDS).answers())
                .containsEntry("deploy_env", List.of("预发"))
                .containsEntry("notify", List.of("要"));

        pushed.clear();
        Future<QuestionGate.Outcome> bad = askInBackground(gate, new QuestionGate.Ask(
                "feishu", "chat-1", asker(), "run-3", questions, Duration.ofSeconds(5)));
        awaitPushed("chat-1");
        assertThat(gate.answer("feishu", "chat-1", asker(), "两个都行")).isTrue();
        assertThat(bad.get(3, TimeUnit.SECONDS).status())
                .as("拆不开就按没答继续：让模型带着一个没人做过的选择往下走更糟")
                .isEqualTo(QuestionGate.Status.NO_ANSWER);
    }

    @Test
    @DisplayName("两题原文一模一样、id 不同：答案各归各的，不会串")
    void answersAreKeyedByIdNotByQuestionText() throws Exception {
        PendingQuestions gate = gate();
        List<QuestionGate.Question> questions = List.of(
                question("east", "哪个区？", "华东", "华南", "华北"),
                question("south", "哪个区？", "华东", "华南", "华北"));

        Future<QuestionGate.Outcome> outcome = askInBackground(gate, new QuestionGate.Ask(
                "feishu", "chat-1", asker(), "run-1", questions, Duration.ofSeconds(5)));
        awaitPushed("chat-1");
        assertThat(gate.answer("feishu", "chat-1", asker(), "1\n2")).isTrue();

        assertThat(outcome.get(3, TimeUnit.SECONDS).answers())
                .as("按题目原文当键的话，两题会写进同一个格子——后一个覆盖前一个，模型拿到两个一样的答案")
                .containsEntry("east", List.of("华东"))
                .containsEntry("south", List.of("华南"));
    }

    @Test
    @DisplayName("模型给错形状时改好而不是抛异常：缺 id 补 q1、header 截 12 字、label 截 64 字")
    void malformedShapeIsRepairedNotThrown() {
        List<QuestionGate.Ask> seen = new ArrayList<>();
        QuestionGate capturing = new QuestionGate() {

            @Override
            public Outcome ask(Ask ask) {
                seen.add(ask);
                return Outcome.noAnswer();
            }

            @Override
            public boolean answer(String channel, String chatRef, Principal who, String text) {
                return false;
            }
        };
        ToolContext context = new ToolContext(Map.of(
                ContextKeys.PRINCIPAL, asker(),
                ContextKeys.CHAT_REF, "chat-1",
                ContextKeys.CHANNEL, "feishu"));

        String result = new AskUserTool(capturing).askUserQuestion(List.of(
                new AskUserTool.Question(null, "部署目标环境这个标签特别长", "部署到哪？",
                        List.of(new AskUserTool.Option("x".repeat(80), null)), false)), context);

        assertThat(result).as("工具不该抛异常：一抛这一轮就断了").contains("no_answer");
        QuestionGate.Question asked = seen.getFirst().questions().getFirst();
        assertThat(asked.id()).as("缺 id 就补一个，不然答案没地方放").isEqualTo("q1");
        assertThat(asked.header()).as("芯片位放不下长标签").hasSize(12);
        assertThat(asked.options().getFirst().label()).hasSize(64);
    }

    @Test
    @DisplayName("子 Agent 的工具面里没有提问工具：问不出来就别摆在眼前")
    void subagentsDoNotGetTheAskTool() {
        ToolCallback ask = MethodToolCallbackProvider.builder()
                .toolObjects(new AskUserTool(QuestionGate.unsupported()))
                .build()
                .getToolCallbacks()[0];
        ToolCallback other = MethodToolCallbackProvider.builder()
                .toolObjects(new DemoTools())
                .build()
                .getToolCallbacks()[0];

        assertThat(AskUserTool.mainSessionOnly(List.of(ask, other)))
                .as("提问会把这一轮停下来等人，而没人盯着子 Agent 那个会话等一句答案")
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("demo_echo");
    }

    @Test
    @DisplayName("描述里有 OpenClaw 那几条反向约束：不许拿它问能否继续、不许在正文里列选项代替它")
    void descriptionCarriesTheTwoWayContract() {
        String description = MethodToolCallbackProvider.builder()
                .toolObjects(new AskUserTool(QuestionGate.unsupported()))
                .build()
                .getToolCallbacks()[0].getToolDefinition().description();

        assertThat(description)
                .as("只说\"何时该用\"的话，模型会在正文里写两个选项了事——这正是 B7 一直红的原因")
                .contains("Do not list the options as plain text in your reply")
                .contains("Do not use it to ask whether you may continue")
                .contains("(Recommended)");
    }

    private static QuestionGate.Question question(String id, String text, String... labels) {
        List<QuestionGate.Option> options = Arrays.stream(labels)
                .map(label -> new QuestionGate.Option(label, null))
                .toList();
        return new QuestionGate.Question(id, "部署", text, options, false);
    }

    private static List<QuestionGate.Question> singleChoice() {
        return List.of(question("deploy_target", QUESTION, "生产", "预发"));
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
        return new Principal("u1");
    }

    private static Principal other() {
        return new Principal("u2");
    }

    /** 一个与提问无关的工具：用来验"剔掉提问工具"只剔它自己。 */
    static final class DemoTools {

        @Tool(name = "demo_echo", description = "Echo the text back.")
        public String echo(@ToolParam(description = "Text to echo") String text) {
            return text;
        }
    }
}
