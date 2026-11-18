package io.github.claw4j.agent.question;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.question.QuestionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.util.json.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把库里的 {@code AskUserQuestionTool} 接到本项目的提问登记表上。
 *
 * <p>为什么中间要垫一层：库的 {@code QuestionHandler} 是 {@code Map<String,String> handle(List<Question>)}，
 * 签名里<b>没有 {@code ToolContext}</b>。而"这段对话在哪、谁在问、哪一轮"恰好都在 {@code ToolContext} 里
 * （{@link ContextKeys}），模型没有参数能改它们。于是这一层只干一件事：调用期间把这四样放进
 * {@link ThreadLocal}，handler 从那儿取，调完就清。库那一层不动——工具名、Schema、"一到四个问题"
 * 与"选项二到四个"的校验都还是它的。
 *
 * <p>不用静态字段传而是 {@code ThreadLocal}：工具回调是同步的，handler 与 {@code call} 在同一个线程上，
 * 用 {@code ThreadLocal} 不会串到并发的另一轮；换成静态字段的话，两个用户同时提问会互相拿到对方的通道。
 *
 * <p>返回值不是库拼的那句 {@code User has answered your questions: …}，而是本层按三种结局自己组：
 * 没答上时那句话会说"已经答了"，模型看到就会当成拿到了答案。
 */
final class AskUserTool {

    private static final Logger log = LoggerFactory.getLogger(AskUserTool.class);

    /** 这一轮的地址与身份。handler 拿不到 {@code ToolContext}，只能从这里读。 */
    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();

    /** 这一次提问的结局。{@code call} 要用它组返回值。 */
    private static final ThreadLocal<QuestionGate.Outcome> OUTCOME = new ThreadLocal<>();

    private AskUserTool() {
    }

    /**
     * 建工具回调。
     *
     * @param gate 提问登记表
     * @return 名为 {@code AskUserQuestionTool} 的工具回调
     */
    static ToolCallback callback(QuestionGate gate) {
        AskUserQuestionTool library = AskUserQuestionTool.builder()
                .questionHandler(questions -> handle(gate, questions))
                .build();
        ToolCallback delegate = MethodToolCallbackProvider.builder()
                .toolObjects(library)
                .build()
                .getToolCallbacks()[0];
        return new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                Call call = callOf(toolContext);
                if (call == null) {
                    // 没有通道地址（子 Agent、脚本里那种没有对话的回调）：照"不支持"办。
                    log.warn("这次提问拿不到通道地址（ToolContext 里没有 principal 或 chatRef），按不支持处理");
                    return render(QuestionGate.Outcome.unsupported());
                }
                CURRENT.set(call);
                OUTCOME.remove();
                try {
                    delegate.call(toolInput, toolContext);
                    QuestionGate.Outcome outcome = OUTCOME.get();
                    return render(outcome == null ? QuestionGate.Outcome.noAnswer() : outcome);
                }
                finally {
                    CURRENT.remove();
                    OUTCOME.remove();
                }
            }
        };
    }

    /**
     * 库的 handler：把问题交给登记表，等人答。
     *
     * <p>返回的 map 是库要的形状——<b>按题目原文分组</b>、每题都要有键、值不能为 {@code null}
     * （{@code AskUserQuestionTool#validateAnswers}）。没答上时给空串：库只校验形状，
     * 真正的结局由 {@link #render} 说。
     *
     * @param gate 提问登记表
     * @param questions 库提的问题
     * @return 按题目原文分组的答案
     */
    private static Map<String, String> handle(QuestionGate gate, List<AskUserQuestionTool.Question> questions) {
        Call call = CURRENT.get();
        Map<String, String> blanks = blanksOf(questions);
        if (call == null) {
            return blanks;
        }
        QuestionGate.Ask ask = new QuestionGate.Ask(call.channel(), call.chatRef(), call.who(),
                call.runId(), translate(questions), null);
        QuestionGate.Outcome outcome;
        try {
            outcome = gate.ask(ask);
        }
        catch (RuntimeException failure) {
            // 登记表本身出问题不该把这一轮变成 500：模型拿到"没答上"照样能继续。
            return blanks;
        }
        OUTCOME.set(outcome);
        if (!outcome.isAnswered()) {
            return blanks;
        }
        Map<String, String> answers = new LinkedHashMap<>();
        for (AskUserQuestionTool.Question question : questions) {
            List<String> values = outcome.answers().get(question.question());
            answers.put(question.question(), values == null ? "" : String.join(", ", values));
        }
        return answers;
    }

    /** 库的问题类型 → 本项目的。库那边 key 就是题目原文，所以原文一个字都不能动。 */
    private static List<QuestionGate.Question> translate(List<AskUserQuestionTool.Question> questions) {
        return questions.stream()
                .map(question -> new QuestionGate.Question(
                        question.header(),
                        question.question(),
                        question.options() == null ? List.of() : question.options().stream()
                                .map(option -> new QuestionGate.Option(option.label(), option.description()))
                                .toList(),
                        Boolean.TRUE.equals(question.multiSelect())))
                .toList();
    }

    private static Map<String, String> blanksOf(List<AskUserQuestionTool.Question> questions) {
        Map<String, String> blanks = new LinkedHashMap<>();
        for (AskUserQuestionTool.Question question : questions) {
            blanks.put(question.question(), "");
        }
        return blanks;
    }

    /**
     * 结局 → 模型看到的那段文字。
     *
     * <p>三种结局必须长得不一样：都是"答了"的话，模型会把 {@code no_answer} 当成一个人做的决定，
     * 于是拿一个没人做过的选择往下走。
     *
     * @param outcome 结局
     * @return JSON 字符串
     */
    private static String render(QuestionGate.Outcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", outcome.status().name().toLowerCase(Locale.ROOT));
        switch (outcome.status()) {
            case ANSWERED -> payload.put("answers", outcome.answers());
            case NO_ANSWER -> payload.put("note",
                    "没人回答这个问题。按你自己的判断继续，并在回答里说明你选了哪一个、为什么；"
                            + "不要重复问同一个问题。");
            case UNSUPPORTED -> payload.put("note",
                    "这个通道没有交互提问：直接在回答里用普通文字把问题问出来，让用户下一句话回答你。");
        }
        return JsonParser.toJson(payload);
    }

    /** 从工具上下文里读这一轮的地址与身份；缺一个就不问（没有对话可问）。 */
    private static Call callOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Map<String, Object> context = toolContext.getContext();
        if (!(context.get(ContextKeys.PRINCIPAL) instanceof Principal who)) {
            return null;
        }
        Object chatRef = context.get(ContextKeys.CHAT_REF);
        if (chatRef == null) {
            return null;
        }
        Object channel = context.get(ContextKeys.CHANNEL);
        Object runId = context.get(ContextKeys.RUN_ID);
        // timeout 给 null：由登记表按 claw4j.tools.ask.timeout-seconds 决定。工具这一层不持有配置，
        // 两处各有一份默认值的话，"实际等了多久"这两边迟早不一样。
        return new Call(channel == null ? null : channel.toString(), chatRef.toString(), who,
                runId == null ? null : runId.toString());
    }

    /**
     * 这一轮的地址与身份。
     *
     * @param channel 通道标识，可能为 {@code null}（没有通道名的内部调用）
     * @param chatRef 通道内的会话地址
     * @param who 提问人
     * @param runId 这一轮的编号
     */
    private record Call(String channel, String chatRef, Principal who, String runId) {
    }
}
