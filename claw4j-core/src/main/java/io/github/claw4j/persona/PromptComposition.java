package io.github.claw4j.persona;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 把一份人设拼成这次请求要用的系统提示词：正文 + 口径 + 现在的时间。
 *
 * <p><b>为什么时间要拼进来</b>：在此之前模型手上没有时钟，整份代码里没有任何"今天几号"
 * 进入提示词。后果不是"不知道星期几"这么轻——`上周`、`本月`、`最近三天` 这些词
 * 全靠它自己猜，而它猜的是训练数据里的日期。示例里的周报 Skill 是靠 SQL 的
 * {@code current_date} 兜过去的，等于业务口径顺带依赖了数据库时钟；
 * 一旦哪天问的不是 SQL 能回答的东西（"这件事多久没动了"），就没有兜底了。
 *
 * <p><b>为什么带时区</b>：只说"今天是 11 月 3 日"是不完整的。容器里
 * {@code ZoneId.systemDefault()} 通常是 UTC，而业务在 Asia/Shanghai——
 * 差八小时意味着每天晚上八点之后，"今天"在模型那里已经是明天。
 * 时区写进提示词，模型自己就能看见它算的是哪一口钟。
 *
 * <p><b>精度到分钟，不是到秒</b>：秒级时间会让每一轮的提示词都不同，
 * 而提示词相同是缓存、回放、对比测试都能成立的前提。分钟级已经够回答"今天/上周"。
 *
 * <p>这个类是纯函数，不读盘、不看时钟：{@code now} 由调用方每轮传进来。
 * 装配期算一次时间是最容易犯的错——进程跑一周，提示词里的"今天"还是启动那天。
 */
public final class PromptComposition {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    private PromptComposition() {
    }

    /**
     * @param persona  人设（正文就是它的 {@code systemPrompt}）
     * @param glossary 口径；空表示这个人设没有额外口径
     * @param now      这一轮的时间，必须带时区
     */
    public static String compose(Persona persona, List<GlossarySection> glossary, ZonedDateTime now) {
        Objects.requireNonNull(persona, "persona");
        Objects.requireNonNull(glossary, "glossary");
        Objects.requireNonNull(now, "now");

        StringBuilder prompt = new StringBuilder(persona.systemPrompt().strip());

        if (!glossary.isEmpty()) {
            prompt.append("\n\n## 部门口径\n\n")
                    .append("下面这些是这个部署统一的口径。回答里涉及这些数字时按它算，")
                    .append("不要按印象，也不要用你见过的别的公司的算法替它。\n");
            for (GlossarySection section : glossary) {
                prompt.append("\n### 出处：").append(section.source()).append('\n')
                        .append(section.text())
                        .append('\n');
            }
        }

        prompt.append("\n\n## 现在的时间\n\n")
                .append("此刻是 ").append(format(now)).append("。\n")
                .append("涉及“今天 / 昨天 / 上周 / 本月”这类相对时间时按这个算，")
                .append("不要用你记忆里的日期——那是训练时的，不是现在的。\n");
        return prompt.toString();
    }

    /** {@code 2026-11-03 20:28（星期二，Asia/Shanghai）}。单独拿出来是为了用例能直接钉格式。 */
    public static String format(ZonedDateTime now) {
        Objects.requireNonNull(now, "now");
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE);
        return now.format(MINUTE) + "（" + weekday + "，" + now.getZone().getId() + "）";
    }
}
