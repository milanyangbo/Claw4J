package io.github.claw4j.persona;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 把一份人设拼成这次请求要用的系统提示词：正文 + 口径 + 数据范围 + 现在的时间。
 *
 * <p>时间要拼进来，因为模型手上没有时钟：不拼的话 {@code 上周}、{@code 本月}、
 * {@code 最近三天} 全靠它自己猜，而它猜的是训练数据里的日期。示例里的周报 Skill 靠 SQL 的
 * {@code current_date} 兜过去，等于业务口径顺带依赖了数据库时钟；一旦问的不是 SQL 能回答的
 * 东西（"这件事多久没动了"），就没有兜底了。
 *
 * <p>时间要带时区：只说"今天是 11 月 3 日"是不完整的。容器里
 * {@code ZoneId.systemDefault()} 通常是 UTC，而业务在 Asia/Shanghai——差八小时意味着每天晚上
 * 八点之后，"今天"在模型那里已经是明天。时区写进提示词，模型自己就能看见它算的是哪一口钟。
 *
 * <p>精度只到天：框架的 {@code SemanticCacheAdvisor} 用 {@code SHA-256(整份系统提示词)} 当
 * 缓存上下文键，提示词里只要带 {@code HH:mm}，这个键就每分钟变一次，缓存活不过 60 秒。而业务
 * 问的是"今天 / 上周 / 本月"，到天足够；"现在几点"本来也不该拿来算数。
 *
 * <p>数据范围进提示词是同一件事的另一面：那个键只由提示词算出，权限不进提示词，同一个人设下的
 * 所有人就共用一个键——guest 与 admin 问同一句话会互相命中，而两人的答案本来不同。这里放的是
 * 权限指纹（{@code tables + actions} 的哈希），不是表清单：清单会变成权限配置的第二份口径，
 * 还得跟着注册表同步；指纹只是一个稳定标识，模型读不懂也不需要读懂，它的作用是让"权限不同"在
 * 缓存键上可见。
 *
 * <p>这个类是纯函数，不读盘、不看时钟：{@code now} 由调用方每轮传进来。装配期算一次时间是最
 * 容易犯的错——进程跑一周，提示词里的"今天"还是启动那天。
 */
public final class PromptComposition {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    private PromptComposition() {
    }

    /**
     * 不带数据范围：没有 {@code Principal} 的调用（子代理、回放、用例）走这里。
     *
     * <p>此时提示词里没有权限段，缓存键与"任何身份"都不同——这比复用一个带权限的键安全：
     * 无身份的一轮不该命中任何人的答案。
     *
     * @param persona  人设
     * @param glossary 口径；空表示这个人设没有额外口径
     * @param now      这一轮的时间，必须带时区
     * @return 拼好的系统提示词
     */
    public static String compose(Persona persona, List<GlossarySection> glossary, ZonedDateTime now) {
        return compose(persona, glossary, now, null);
    }

    /**
     * 拼出带权限指纹的系统提示词。
     *
     * @param persona   人设（正文就是它的 {@code systemPrompt}）
     * @param glossary  口径；空表示这个人设没有额外口径
     * @param now       这一轮的时间，必须带时区
     * @param dataScope 权限指纹；{@code null} 或空白表示不写这一段
     * @return 拼好的系统提示词
     */
    public static String compose(Persona persona, List<GlossarySection> glossary, ZonedDateTime now,
                                 String dataScope) {
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

        if (dataScope != null && !dataScope.isBlank()) {
            prompt.append("\n\n## 数据范围\n\n")
                    .append("本次会话的数据权限标识：").append(dataScope).append('\n')
                    .append("只能使用工具允许你访问的数据；没有工具能拿到的数据，")
                    .append("直接说明拿不到，不要凭猜测补。\n");
        }

        prompt.append("\n\n## 现在的时间\n\n")
                .append("此刻是 ").append(format(now)).append("。\n")
                .append("涉及“今天 / 昨天 / 上周 / 本月”这类相对时间时按这个算，")
                .append("不要用你记忆里的日期——那是训练时的，不是现在的。\n");
        return prompt.toString();
    }

    /**
     * 把时间写成 {@code 2026-11-09（星期一，Asia/Shanghai）}。
     *
     * <p>单独拿出来是为了用例能直接钉格式。
     *
     * @param now 要格式化的时间
     * @return 带星期与时区的时间串
     */
    public static String format(ZonedDateTime now) {
        Objects.requireNonNull(now, "now");
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE);
        return now.format(DAY) + "（" + weekday + "，" + now.getZone().getId() + "）";
    }
}
