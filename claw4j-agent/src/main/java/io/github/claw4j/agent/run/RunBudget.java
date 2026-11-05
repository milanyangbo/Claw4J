package io.github.claw4j.agent.run;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 一段会话的预算：三项按会话累计，空闲超时即重置（A04，ADR-0025 改挂会话）。
 *
 * <p><b>为什么挂在会话上而不是 run 上</b>：run 的寿命是一轮——每轮收尾都
 * {@code finish(runId, DONE)}，下一轮开的是新 run。按 run 记的数在每轮开头都是 0/6，
 * "跨轮累计"从来没有发生过：三轮各调 6 次一共 18 次，全部放行。
 * 挂到会话上，"这段对话一共能烧多少"才有地方记。
 *
 * <p><b>会话什么时候结束：由段说了算，这里只跟着它</b>（ADR-0026）。空闲阈值
 * （{@code claw4j.session.reset.idle-minutes}，默认 120）在会话那一边；
 * 换段之后 {@code session_id} 变了，这份额度自然从 0 开始——所以本类不再自己判一次空闲。
 * 两处都判的话，下一次改 idle 的人只会改一处，另一处看起来还在生效。
 *
 * <p><b>为什么还留着一个空闲窗口</b>：{@code spent(runId)} 是"这条 run 所属那段会话用了多少"，
 * 而旧数据（段上线之前建的 run）的 {@code session_id} 永远不变、永远不会换段。
 * 给一个窗口，那些行才不会把额度永久钉死。新数据走换段，窗口对它们是恒真的。
 *
 * <p><b>读不到用量时放行，不拦活</b>：库没迁、连不上，或者这个 runId 已经不在了，
 * {@link #spent} 就是空。那种情况下预算管不住，而管不住的失败姿势应该是
 * "这一轮少记一笔"，不是"把用户的活停了"——与 run 落库降级是同一个判断
 * （{@code JdbcRunStore} 的注释：run 是记账与审计的挂载点，不是回答的前置条件）。
 * 少记多少由 {@code claw4j.run.errors} 暴露，别让它变成静默。
 *
 * <p><b>先问再扣，不是先扣再判</b>：一次工具调用要么发生、要么被拒，
 * 被拒的那一次不该被记成用掉的量——否则"还剩几次"这个数会凭空少一次，
 * 而它正是决定下一次给不给的那一个数。
 *
 * <p><b>子 Agent 那一侧还没有 sessionId</b>：委派发生在工具内部，工具上下文里没有
 * 父这一轮的会话键（{@code SubagentRunner} 目前只传身份），所以子调的工具
 * 现在不扣父的预算。那是 A10 的事，不是这里漏了一条。
 */
public final class RunBudget {

    private final RunStore runs;

    private final RunBudgetProperties caps;

    /** 空闲窗口，毫秒。只对"不换段的旧会话"有意义，见类注释最后一段。 */
    private final long idleMillis;

    public RunBudget(RunStore runs, RunBudgetProperties caps, long idleMillis) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.caps = Objects.requireNonNull(caps, "caps");
        this.idleMillis = idleMillis;
    }

    /**
     * 这段会话在空闲窗口内已经用掉多少。空表示"读不到"（没有这个 run，或存储降级）。
     *
     * <p>窗口起点是"现在减去空闲时长"：超过它没动过的会话视为已结束，计数从 0 重新开始。
     * 这就是"什么时候算完"的答案——不问任务做没做完，只问人还有没有在说。
     */
    public Optional<Budget> spent(String runId) {
        return runs.spent(runId, Instant.now().minusMillis(idleMillis));
    }

    /** 三项里有没有一项已经撞线。 */
    public boolean exhausted(Budget spent) {
        return spent.toolCalls() >= caps.maxToolCalls()
                || spent.tokens() >= caps.maxTokens()
                || spent.millis() >= caps.maxMillis();
    }

    /**
     * 撞线的是哪一项。写明结局用的就是它，所以带两个数：用掉的与上限。
     *
     * <p>只在 {@link #exhausted} 为真时调用；没撞线时它回的是耗时那一项，
     * 那不是"原因"。调用方先判再用，是这里不自己判第二次的原因——
     * 两处都判的话，加一项就得记得改两处。
     */
    public String reason(Budget spent) {
        if (spent.toolCalls() >= caps.maxToolCalls()) {
            return "工具调用 " + spent.toolCalls() + "/" + caps.maxToolCalls() + " 次";
        }
        if (spent.tokens() >= caps.maxTokens()) {
            return "token " + spent.tokens() + "/" + caps.maxTokens();
        }
        return "耗时 " + spent.millis() / 1000 + "/" + caps.maxSeconds() + " 秒";
    }

    /**
     * 记一次工具调用：工具调用这一项还没撞线就扣一笔。
     *
     * <p><b>它只判一维，也不负责"拦"</b>：三维（次数 / token / 耗时）的判据是 {@link #exhausted}，
     * 拦不拦由调用方决定——{@code MeteredToolCallback} 先读一次 {@code spent}、用
     * {@code exhausted} 判完再决定要不要执行，执行完才到这里记账。所以这个返回值在生产里
     * 没有人接，它只回答"工具调用这一项还有没有额度"，<b>不回答"这一次该不该执行"</b>：
     * token 撞线时它照样返回 true。两次读之间也没有原子性，把拦截放在这里会漏掉另外两维。
     *
     * @return false = 工具调用这一项已撞线（这一笔没扣）；true = 扣上了，或读不到用量
     *         （管不住的时候不拦活，见类注释）
     */
    public boolean chargeToolCall(String runId) {
        Optional<Budget> spent = spent(runId);
        if (spent.isEmpty()) {
            // 读不到：管不住的时候不拦活（见类注释）。
            return true;
        }
        if (spent.get().toolCalls() >= caps.maxToolCalls()) {
            return false;
        }
        runs.charge(runId, 1, 0L, 0L);
        return true;
    }

    /**
     * 一轮结束后的那一笔：token 与耗时。
     *
     * <p><b>token 拿不到就记 0，不估算</b>：记一个估算值会让成本口径不可信，
     * 而记 0 至少能看出"这一项没数"（与 {@code MeteredChatModel} 同一个口径）。
     * 流式那一侧拿不到汇总 usage，同样是 0。
     */
    public Optional<Budget> chargeTurn(String runId, long tokens, long millis) {
        return runs.charge(runId, 0, tokens, millis);
    }

    /**
     * 给模型的回执：工具调用被拒时它是工具的结果。
     *
     * <p><b>回一句话而不是抛异常</b>：抛出去会把整轮打成 500，而 500 与
     * "这一件事的额度用完了"是两件代价完全不同的事——后者模型能自己收尾，
     * 前者用户只能重问一遍，而重问一遍会再开一个 run。
     */
    public String refusalText(Budget spent) {
        return "预算用尽（" + reason(spent) + "）：这一次调用没有执行。"
                + "不要再调工具，把已经拿到的那部分结论直接写出来，并说明停在哪里。";
    }

    /**
     * 给人的结局：这件事为什么停了、停在哪个 run 上、怎么才能继续。
     *
     * <p>与 {@link #refusalText} 同一套措辞（都以"预算用尽"开头）：两处是同一件事的
     * 两个读者，措辞分家的话，日志里会出现两种说法，而它们指的是同一个原因。
     */
    public String outcomeText(Budget spent, String runId) {
        return "预算用尽（" + reason(spent) + "）：这件事停在这里（run "
                + runId.substring(0, Math.min(8, runId.length())) + "），没有再调用模型。"
                + "要接着做，得先把这一项的上限调大。";
    }
}
