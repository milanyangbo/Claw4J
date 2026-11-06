package io.github.claw4j.agent.goal;

import java.util.Locale;
import java.util.Optional;

/**
 * 消息里的 {@code /goal} 命令族（ADR-0027 决定三）。
 *
 * <p><b>为什么与 {@code /new} 一样是消息</b>：OpenClaw 的 {@code /goal} 就写在消息里，
 * 并且明确"纯命令消息会立即处理（绕过队列和模型）"。落在这一层的好处与 {@code /new} 同源：
 * 三条通道自动都有，不用给每个通道注册斜杠命令；命令本身不进提示词、不占一次模型调用。
 *
 * <p><b>动作词表照原文</b>：{@code status} / {@code start} / {@code set} / {@code create} /
 * {@code edit} / {@code pause} / {@code resume} / {@code complete} / {@code done} /
 * {@code block} / {@code blocked} / {@code clear}。其中 {@code set} 与 {@code create}
 * 是 {@code start} 的别名，{@code done} 与 {@code blocked} 各是 {@code complete} 与
 * {@code block} 的别名——别名不是我们发明的，是它的命令表里就有的。
 *
 * <p><b>其余任何词都是新目标</b>：OpenClaw 的原话是"{@code /goal} 后面任何不是已知操作词的
 * 文本都会被视为新目标"。所以 {@code /goal 让 CI 变绿} 建目标，而 {@code /goal start 让 CI 变绿}
 * 与它等价。
 *
 * <p><b>{@code start} 与 {@code edit} 保留原文</b>：换行、缩进、连续空格都不动，只裁首尾。
 * 一句话的目标与七行的清单都该原样进去——把换行折成空格会让一份清单变成一段读不懂的散文。
 *
 * <p><b>解析与执行放在一起</b>：认出来了但没有目标、认出来了但参数不对、根本没认出来，
 * 这三种情况对用户说的是三句不同的话。拆成两个类型的话，那些话会分散在两处，
 * 而"这句提示到底谁负责"正是最容易长歪的地方。
 */
public sealed interface GoalCommand {

    /** {@code /goal}：看当前目标。 */
    record Show() implements GoalCommand {
    }

    /** 新建。 */
    record Start(String objective) implements GoalCommand {
    }

    /** 改写内容，状态与用量不变。 */
    record Edit(String objective) implements GoalCommand {
    }

    /** 暂停。只有人能按。 */
    record Pause(String note) implements GoalCommand {
    }

    /** 恢复。只有人能按。 */
    record Resume(String note) implements GoalCommand {
    }

    /** 达成。 */
    record Complete(String note) implements GoalCommand {
    }

    /** 阻塞。 */
    record Block(String note) implements GoalCommand {
    }

    /** 删掉这一行。 */
    record Clear() implements GoalCommand {
    }

    /** 认出来是 {@code /goal}，但参数不对。 */
    record Invalid(String reason) implements GoalCommand {
    }

    /** 命令词。整词匹配，{@code /goals} 不是命令。 */
    String HEAD = "/goal";

    /**
     * 拆一条消息。
     *
     * @return 空表示这条消息不是 {@code /goal} 命令——它该原样进模型
     */
    static Optional<GoalCommand> parse(String text) {
        String stripped = (text == null ? "" : text).strip();
        if (!stripped.startsWith(HEAD)) {
            return Optional.empty();
        }
        if (stripped.length() > HEAD.length()
                && !Character.isWhitespace(stripped.charAt(HEAD.length()))) {
            // /goals、/goalkeeper：前缀匹配会把一个普通问题当成命令，
            // 而用户看到的是"我的问题它没回答"，没有任何报错。
            return Optional.empty();
        }
        // 只裁首尾：中间那几个换行是目标原文的一部分。
        String rest = stripped.substring(HEAD.length()).strip();
        if (rest.isEmpty()) {
            return Optional.of(new Show());
        }
        int split = firstWhitespace(rest);
        String head = split < 0 ? rest : rest.substring(0, split);
        // 未知动作词走 default，那时目标要从 rest 整段取——不能取 tail，
        // 目标的第一词正是那个"未知动作词"。
        String tail = split < 0 ? "" : rest.substring(split).strip();
        return Optional.of(switch (head.toLowerCase(Locale.ROOT)) {
            case "status" -> new Show();
            case "start", "set", "create" -> start(tail);
            case "edit" -> tail.isEmpty()
                    ? new Invalid("要改写成什么？用法：/goal edit <目标>")
                    : new Edit(tail);
            case "pause" -> new Pause(note(tail));
            case "resume" -> new Resume(note(tail));
            case "complete", "done" -> new Complete(note(tail));
            case "block", "blocked" -> new Block(note(tail));
            case "clear" -> new Clear();
            default -> new Start(rest);
        });
    }

    /**
     * 执行一条命令，回一句话。
     *
     * @param goals     目标存储；为 null 表示这个部署没装（与"没有目标"是两件事）
     * @param sessionKey 当前这一段对话的键（含段）。{@code /new} 之后它是新段的键，
     *                   于是"换段即无目标"不需要额外 delete
     */
    static String execute(GoalCommand command, SessionGoalStore goals, String sessionKey) {
        if (goals == null || sessionKey == null) {
            return "这个部署没有开目标存储，/goal 这一族命令用不了。";
        }
        return switch (command) {
            case Show ignored -> goals.of(sessionKey).map(GoalTools::render)
                    .orElse("这个会话还没有目标。用法：/goal <目标>，或 /goal start <目标>");
            case Invalid invalid -> invalid.reason();
            case Clear ignored -> goals.clear(sessionKey)
                    ? "目标已清掉。"
                    : "这个会话本来就没有目标。";
            case Start start -> start(goals, sessionKey, start.objective());
            case Edit edit -> edit(goals, sessionKey, edit.objective());
            case Pause pause -> setStatus(goals, sessionKey, GoalStatus.PAUSED, pause.note(),
                    "目标已暂停。接下来的轮次不会再把它注入上下文；/goal resume 继续。");
            case Resume resume -> resume(goals, sessionKey, resume.note());
            case Complete complete -> setStatus(goals, sessionKey, GoalStatus.COMPLETE,
                    complete.note(), "目标已标成达成。要开始别的事得先 /goal clear。");
            case Block block -> setStatus(goals, sessionKey, GoalStatus.BLOCKED, block.note(),
                    "目标已标成阻塞。有新进展时用 /goal resume 继续。");
        };
    }

    private static String start(SessionGoalStore goals, String sessionKey, String objective) {
        return goals.start(sessionKey, objective, null)
                .map(created -> "已建目标。\n" + GoalTools.render(created))
                .orElseGet(() -> goals.of(sessionKey)
                        .map(existing -> "这个会话已经有目标了，没有新建：\n" + GoalTools.render(existing)
                                + "\n要换目标得先 /goal clear。")
                        .orElse("目标没写进去（库连不上，或者迁移没跑过）。"));
    }

    private static String edit(SessionGoalStore goals, String sessionKey, String objective) {
        return goals.edit(sessionKey, objective)
                .map(changed -> "目标已改写（状态与用量都没动）。\n" + GoalTools.render(changed))
                .orElseGet(() -> goals.of(sessionKey)
                        .map(ignored -> "没改成（库连不上，或者迁移没跑过）。")
                        .orElse("这个会话还没有目标。用法：/goal <目标>"));
    }

    /**
     * 恢复。
     *
     * <p>已完成的目标不能恢复：那是终态，OpenClaw 的原文是"目标处于终止状态。
     * 启动或恢复其他目标前，请先将其清除"。恢复它等于把一件收尾的事重新打开。
     *
     * <p><b>从 {@code budget_limited} 恢复会开新窗口</b>：用量清零、预算不变
     * （"Resuming starts a new budget window at the current fresh token count"）。
     * 清零这件事在 {@link SessionGoalStore#resume} 的那一条 SQL 里，因为只有它能原子地
     * 回答"更新之前是不是 budget_limited"。
     */
    private static String resume(SessionGoalStore goals, String sessionKey, String note) {
        Optional<SessionGoal> current = goals.of(sessionKey);
        if (current.isEmpty()) {
            return "这个会话还没有目标。用法：/goal <目标>";
        }
        if (!current.get().status().resumable()) {
            return "这个目标已经达成了，不能恢复。要开始别的事得先 /goal clear。";
        }
        if (current.get().status() == GoalStatus.ACTIVE) {
            // 原文的 resume 是"恢复已暂停、已阻塞、用量受限或预算受限的目标"。
            // 打在活跃目标上什么都不该发生：跟着改一格 revision 的话，
            // "谁最后改的"就多出一条没有改动的痕迹。
            return "这个目标本来就是活跃的，没动它。\n" + GoalTools.render(current.get());
        }
        boolean freshWindow = current.get().status() == GoalStatus.BUDGET_LIMITED;
        return goals.resume(sessionKey, note)
                .map(changed -> freshWindow
                        ? "目标恢复为活跃，额度开了新窗口（用量从 0 起算）。接下来的轮次会重新把它带进上下文。"
                        : "目标恢复为活跃，接下来的轮次会重新把它带进上下文。")
                .orElseGet(() -> goals.of(sessionKey)
                        .map(ignored -> "没能改（库连不上，或者迁移没跑过）。")
                        .orElse("这个会话还没有目标。用法：/goal <目标>"));
    }

    private static String setStatus(SessionGoalStore goals, String sessionKey, GoalStatus status,
                                    String note, String done) {
        return goals.setStatus(sessionKey, status, note)
                .map(changed -> done)
                .orElseGet(() -> goals.of(sessionKey)
                        .map(ignored -> "没能改（库连不上，或者迁移没跑过）。")
                        .orElse("这个会话还没有目标。用法：/goal <目标>"));
    }

    private static GoalCommand start(String objective) {
        return objective.isEmpty()
                ? new Invalid("目标是空的。用法：/goal <目标> 或 /goal start <目标>")
                : new Start(objective);
    }

    private static String note(String tail) {
        return tail.isEmpty() ? null : tail;
    }

    private static int firstWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
