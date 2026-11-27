package io.github.claw4j.agent.question;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.question.QuestionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.util.json.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 提问工具：模型在<b>同一轮里</b>问人一句，等人答，拿到答案再往下走（照 OpenClaw 的 {@code ask_user}）。
 *
 * <p><b>为什么不再用库那个 {@code AskUserQuestionTool}</b>：它的 {@code Question} 里没有 {@code id}，
 * 答案只能按<b>题目原文</b>当 key 回来（{@code Map<String,String>} 以题面为键）。题面是模型随口写的，
 * 两题写成同一句、或者它自己换了个说法，答案就会串到另一题上。OpenClaw 的 {@code ask_user} 用
 * {@code id} 当答案键（{@code snake_case}，如 {@code deploy_target}），我们照它来。
 * 换了之后还顺带解决了两件事：描述可以完全由我们说（库那份只讲"何时该用"），
 * 以及 {@code Option#description} 从必填变可选（OpenClaw 那边就是可选）。
 *
 * <p><b>校验一律"改好"而不是"抛异常"</b>：模型给错形状是常事，而这里是工具调用——一抛异常这一轮就断了，
 * 用户一个字都拿不到。所以 {@code id} 缺失/重复/不合规补成 {@code q1..qn}，{@code header} 与
 * {@code label} 超长就截断；只有"问题条数超了"这种没法替它决定的，才用一段文字让它拆开再问。
 *
 * <p>返回值不是库那句 {@code User has answered your questions: …}：没答上时那句话会说"已经答了"，
 * 模型看到就会当成拿到了答案。
 */
public final class AskUserTool {

    /** 工具名。子 Agent 那一侧按它剔掉（见 {@code SubagentRunner}）。 */
    public static final String TOOL_NAME = "AskUserQuestionTool";

    /** 短标签上限，与 OpenClaw 同（界面上当芯片用，再长就撑坏了）。 */
    private static final int MAX_HEADER = 12;

    /** 选项名上限，与 OpenClaw 同。 */
    private static final int MAX_LABEL = 64;

    /** 一次最多问几个。库是 1–4；OpenClaw 是 1–3（它的 3 是为面板分步，我们的渲染摆得下任何条数）。 */
    private static final int MAX_QUESTIONS = 4;

    private static final Logger log = LoggerFactory.getLogger(AskUserTool.class);

    private final QuestionGate gate;

    /**
     * @param gate 提问登记表
     */
    public AskUserTool(QuestionGate gate) {
        this.gate = gate;
    }

    /**
     * 问用户。阻塞到有人答、超时、或者发现这个通道没有提问面。
     *
     * @param questions 一到四个问题
     * @param toolContext 这一轮的地址与身份（模型改不了）
     * @return JSON：{@code answered} 带答案，{@code no_answer} / {@code unsupported} 带一句怎么办
     */
    @Tool(name = TOOL_NAME, description = """
             Ask the user when a decision belongs to them and you cannot go on without it.
             This allows you to:
             1. Gather user preferences or requirements
             2. Clarify ambiguous instructions
             3. Get decisions on implementation choices as you work
             4. Offer choices to the user about what direction to take

             Usage notes:
             - When the user should pick, ask here. Do not list the options as plain text in your reply instead:
               a list in chat cannot be answered in one step, and it does not offer free-form input the way this does.
             - Do not use it to ask whether you may continue, and do not use it to confirm your own plan.
               Permission and plan review have their own mechanisms.
             - Put every choice in options; never describe the choices only in the question text.
             - Give each question a short header (at most 12 characters) and a unique snake_case id.
             - Set multiSelect only when several options can be picked together.
             - If you recommend an option, put it first and end its label with "(Recommended)".
             - Do not add an "Other" option: free-form input is always available.
             - If the answer comes back as no_answer, continue with your best judgment and say which way you went and why.
            """)
    public String askUserQuestion(
            @ToolParam(description = "Questions to ask the user (1-4 questions)") List<Question> questions,
            ToolContext toolContext) {

        List<QuestionGate.Question> normalized = normalize(questions);
        if (normalized == null) {
            return reject("至少要有一个问题，而且每题都要有问句。把问题补齐再问一次。");
        }
        if (normalized.size() > MAX_QUESTIONS) {
            return reject("一次最多问 " + MAX_QUESTIONS + " 个问题，这次收到 " + normalized.size()
                    + " 个。先问最要紧的那几个，剩下的下一轮再问。");
        }
        Call call = callOf(toolContext);
        if (call == null) {
            // 没有对话可问（headless / 定时任务 / 子 Agent 兜底）：照"这个面没有提问"办。
            log.info("这次提问拿不到通道地址（ToolContext 里没有 principal 或 chatRef），按没有提问面处理");
            return render(QuestionGate.Outcome.unsupported());
        }
        QuestionGate.Ask ask = new QuestionGate.Ask(call.channel(), call.chatRef(), call.who(),
                call.executionId(), normalized, null);
        QuestionGate.Outcome outcome;
        try {
            outcome = gate.ask(ask);
        }
        catch (RuntimeException failure) {
            // 登记表本身出问题不该把这一轮变成 500：模型拿到"没答上"照样能继续。
            log.warn("提问这一路崩了，按没答处理：{}", failure.toString());
            return render(QuestionGate.Outcome.noAnswer());
        }
        return render(outcome);
    }

    /**
     * 子链的工具面：把"向用户提问"剔掉（照 OpenClaw，{@code ask_user} 只在主会话可用）。
     *
     * <p>为什么不是"给了但运行期回一句不支持"：那样模型会白调一次，而那句"直接在回答里用文字问"
     * 反过来教它"用文字问也行"。工具的可见性就该等于可用性——问不出来就别摆在眼前。
     *
     * <p>更要紧的是阻塞：提问会把这一轮停下来等人，而子 Agent 的产出是交给父链的一段结论，
     * 没有任何人盯着它那个会话等一句话。真挂上去的结果是父链陪着一起等满默认的 900 秒。
     *
     * @param selected 子 Agent 选出来的那些工具
     * @return 剔掉提问工具之后的那份；本来没有它就是原样
     */
    public static List<ToolCallback> mainSessionOnly(List<ToolCallback> selected) {
        List<ToolCallback> tools = selected.stream()
                .filter(callback -> !TOOL_NAME.equals(callback.getToolDefinition().name()))
                .toList();
        if (tools.size() != selected.size()) {
            log.debug("子 Agent 不提供 {}：它只在主会话可用", TOOL_NAME);
        }
        return tools;
    }

    /**
     * 把模型给的形状改成可用的：补 id、截 header、截 label、丢掉空题。
     *
     * @param questions 模型给的
     * @return 可用的那几条；一条都用不了时 {@code null}
     */
    private static List<QuestionGate.Question> normalize(List<Question> questions) {
        if (questions == null || questions.isEmpty()) {
            return null;
        }
        List<QuestionGate.Question> normalized = new ArrayList<>(questions.size());
        Set<String> usedIds = new LinkedHashSet<>();
        for (Question question : questions) {
            if (question == null || question.question() == null || question.question().isBlank()) {
                continue;
            }
            int index = normalized.size() + 1;
            String id = uniqueId(question.id(), index, usedIds);
            normalized.add(new QuestionGate.Question(
                    id,
                    cut(question.header(), MAX_HEADER),
                    question.question().strip(),
                    optionsOf(question.options()),
                    Boolean.TRUE.equals(question.multiSelect())));
        }
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 合法且唯一的答案键。
     *
     * <p>模型给的 {@code id} 合规就直接用（这样它写 {@code deploy_target}、我们回 {@code deploy_target}，
     * 两边对得上）；不合规、重复、缺失时补成 {@code q1}、{@code q2}……——最终用的 {@code id} 会随答案一起
     * 回给模型，所以它不必猜。
     *
     * @param raw 模型给的 id
     * @param index 这一题在本次提问里的序号，从 1 起
     * @param used 已经用掉的
     * @return 最终用的 id
     */
    private static String uniqueId(String raw, int index, Set<String> used) {
        String candidate = raw == null ? "" : raw.strip();
        if (!candidate.matches("^[a-z][a-z0-9_]*$") || used.contains(candidate)) {
            candidate = "q" + index;
            int suffix = 2;
            while (used.contains(candidate)) {
                candidate = "q" + index + "_" + suffix++;
            }
        }
        used.add(candidate);
        return candidate;
    }

    private static List<QuestionGate.Option> optionsOf(List<Option> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        List<QuestionGate.Option> normalized = new ArrayList<>(options.size());
        for (Option option : options) {
            if (option == null || option.label() == null || option.label().isBlank()) {
                continue;
            }
            String description = option.description();
            normalized.add(new QuestionGate.Option(
                    cut(option.label(), MAX_LABEL),
                    description == null || description.isBlank() ? null : description.strip()));
        }
        return normalized;
    }

    /**
     * 截到 {@code max} 个字符，不切断代理对（一个 emoji 是两个 char）。
     *
     * @param text 原文，可为 {@code null}
     * @param max 上限
     * @return 截过的；{@code null} 进来 {@code null} 出去
     */
    private static String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        if (stripped.codePointCount(0, stripped.length()) <= max) {
            return stripped;
        }
        return stripped.substring(0, stripped.offsetByCodePoints(0, max));
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

    /** 形状不对时回给模型的一句话。不抛异常：一抛这一轮就断了。 */
    private static String reject(String note) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "invalid");
        payload.put("note", note);
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
        Object executionId = context.get(ContextKeys.EXECUTION_ID);
        // timeout 给 null：由登记表按 claw4j.tools.ask.timeout-seconds 决定。工具这一层不持有配置，
        // 两处各有一份默认值的话，"实际等了多久"这两边迟早不一样。
        return new Call(channel == null ? null : channel.toString(), chatRef.toString(), who,
                executionId == null ? null : executionId.toString());
    }

    /**
     * 这一轮的地址与身份。
     *
     * @param channel 通道标识，可能为 {@code null}（没有通道名的内部调用）
     * @param chatRef 通道内的会话地址
     * @param who 提问人
     * @param executionId 这一轮的编号
     */
    private record Call(String channel, String chatRef, Principal who, String executionId) {
    }

    /**
     * 一道题。
     *
     * @param id 答案键，同一批里唯一，{@code snake_case}
     * @param header 短标签，最多 12 字
     * @param question 一句话问句
     * @param options 选项；可以为空——空表示自由问答
     * @param multiSelect 能不能多选
     */
    public record Question(
            @JsonPropertyDescription("Unique snake_case answer key for this question, e.g. deploy_target. "
                    + "Answers come back keyed by this id.") String id,

            @JsonPropertyDescription("Short label shown as a chip, at most 12 characters.") String header,

            @JsonPropertyDescription("The question, one sentence. All choices must go into options.")
            String question,

            @JsonPropertyDescription("The choices (2-4 when it is a choice question). "
                    + "Leave empty for a free-text answer.") List<Option> options,

            @JsonPropertyDescription("true only when several options can be picked together.")
            Boolean multiSelect) {
    }

    /**
     * 一个选项。
     *
     * @param label 选项名，一到五个词，最多 64 字
     * @param description 一句解释，可选
     */
    public record Option(
            @JsonPropertyDescription("Display text the user picks, 1-5 words, at most 64 characters.")
            String label,

            @JsonPropertyDescription("Optional: what this option means or what happens if chosen.")
            String description) {
    }
}
