package io.github.claw4j.example.mcp.server;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 五字段 cron 的解析与求解：{@code 分 时 日 月 周}。
 *
 * <p>写这一份而不是引一个库，理由是它是这个示例 server 里唯一"真的有逻辑"的东西：
 * 示例要证明的是"server 侧就是普通 Java"，不是"能回显一句话"。
 *
 * <p>支持 {@code *}、{@code n}、{@code a-b}、{@code *}{@code /n}、{@code a-b/n} 与逗号列表；
 * 星期里 {@code 0} 与 {@code 7} 都是周日。
 *
 * <p>一处<b>故意与 Unix cron 不同</b>：日与周同时限制时这里直接拒绝，而 Unix 取两者的并集。
 * 并集语义是"任一命中就跑"，写错的代价是一条任务每月多跑几次而没人发现；拒绝的代价是
 * 表达式写不出来，当场就知道。示例里选后者，并在报错里说清该怎么改写。
 */
final class CronSchedule {

    /**
     * 一分钟一步的求解上限：四年。
     *
     * <p>够覆盖"2 月 30 日"这类永远不成立的写法——它会走满上限然后返回空，调用方据此报
     * "四年内没有匹配"，而不是把线程挂死。
     */
    private static final int MAX_MINUTES = 366 * 24 * 60 * 4;

    private final BitSet minutes;

    private final BitSet hours;

    private final BitSet daysOfMonth;

    private final BitSet months;

    private final BitSet daysOfWeek;

    private final boolean dayRestricted;

    private final boolean weekRestricted;

    private CronSchedule(BitSet minutes, BitSet hours, BitSet daysOfMonth, BitSet months, BitSet daysOfWeek,
                         boolean dayRestricted, boolean weekRestricted) {
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.dayRestricted = dayRestricted;
        this.weekRestricted = weekRestricted;
    }

    /**
     * 解析一条表达式。
     *
     * @param expression 五字段表达式，例如 {@code 0 9 * * 1-5}
     * @return 解析结果，可以反复求解
     * @throws IllegalArgumentException 字段数不对、某个值越界、步长为零，或日与周同时限制
     */
    static CronSchedule parse(String expression) {
        String text = expression == null ? "" : expression.trim();
        String[] fields = text.isEmpty() ? new String[0] : text.split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("cron 要 5 个字段（分 时 日 月 周），实际 " + fields.length
                    + " 个：" + text + "。不支持秒与年那两个扩展字段");
        }

        Parsed minute = parseField(fields[0], 0, 59, "分", text);
        Parsed hour = parseField(fields[1], 0, 23, "时", text);
        Parsed day = parseField(fields[2], 1, 31, "日", text);
        Parsed month = parseField(fields[3], 1, 12, "月", text);
        Parsed week = parseField(fields[4], 0, 7, "周", text);

        // 星期里 7 与 0 是同一个周日：先归一，后面的匹配只认 0，省掉两处特判
        if (week.values().get(7)) {
            week.values().set(0);
            week.values().clear(7);
        }

        if (day.restricted() && week.restricted()) {
            throw new IllegalArgumentException("日与周不能同时限制（" + text + "）：Unix cron 取两者的并集，"
                    + "写错就是每月多跑几次而没人发现。要「每月 1 号」就只写日（0 0 1 * *），"
                    + "要「每周一」就只写周（0 0 * * 1）");
        }

        return new CronSchedule(minute.values(), hour.values(), day.values(), month.values(), week.values(),
                day.restricted(), week.restricted());
    }

    /**
     * 求接下来几次触发时间。
     *
     * @param zone  时区，触发时间按它解释
     * @param after 从这个时刻之后开始算（不含这一分钟）
     * @param count 要几次，必须为正
     * @return 严格递增的本地时间；四年内凑不满时返回实际能凑到的那些
     */
    List<LocalDateTime> next(ZoneId zone, Instant after, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count 要大于 0，实际 " + count);
        }
        List<LocalDateTime> runs = new ArrayList<>(count);
        ZonedDateTime cursor = ZonedDateTime.ofInstant(after, zone)
                .plusMinutes(1)
                .withSecond(0)
                .withNano(0);

        for (int step = 0; step < MAX_MINUTES && runs.size() < count; step++) {
            if (!months.get(cursor.getMonthValue())) {
                cursor = startOfNextMonth(cursor, zone);
            }
            else if (!dayMatches(cursor)) {
                cursor = cursor.toLocalDate().plusDays(1).atStartOfDay(zone);
            }
            else if (!hours.get(cursor.getHour())) {
                cursor = cursor.truncatedTo(ChronoUnit.HOURS).plusHours(1);
            }
            else if (!minutes.get(cursor.getMinute())) {
                cursor = cursor.plusMinutes(1);
            }
            else {
                runs.add(cursor.toLocalDateTime());
                cursor = cursor.plusMinutes(1);
            }
        }
        return runs;
    }

    private boolean dayMatches(ZonedDateTime moment) {
        LocalDate date = moment.toLocalDate();
        if (dayRestricted && !daysOfMonth.get(date.getDayOfMonth())) {
            return false;
        }
        // DayOfWeek 的 1..7 是周一到周日，cron 的 0..6 是周日到周六
        return !weekRestricted || daysOfWeek.get(date.getDayOfWeek().getValue() % 7);
    }

    private static ZonedDateTime startOfNextMonth(ZonedDateTime cursor, ZoneId zone) {
        return cursor.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone);
    }

    /**
     * 解析一个字段。
     *
     * @param spec       字段原文
     * @param min        允许的最小值
     * @param max        允许的最大值
     * @param name       字段名，只用于报错
     * @param expression 整条表达式，只用于报错
     * @return 命中集合与"是否限定过"
     */
    private static Parsed parseField(String spec, int min, int max, String name, String expression) {
        BitSet values = new BitSet(max + 1);
        for (String part : spec.split(",")) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException(name + "字段里有空的列表项（" + expression + "）");
            }
            String range = part;
            int step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                step = number(part.substring(slash + 1), name, expression, "步长");
                if (step <= 0) {
                    throw new IllegalArgumentException(name + "字段的步长要大于 0（" + expression + "）");
                }
            }

            int from;
            int to;
            if ("*".equals(range)) {
                from = min;
                to = max;
            }
            else if (range.indexOf('-') > 0) {
                int dash = range.indexOf('-');
                from = number(range.substring(0, dash), name, expression, "起点");
                to = number(range.substring(dash + 1), name, expression, "终点");
            }
            else {
                from = number(range, name, expression, "值");
                to = from;
            }

            if (from < min || to > max || to < from) {
                throw new IllegalArgumentException(name + "字段的值要在 " + min + ".." + max + " 之间且升序（"
                        + expression + "）");
            }
            for (int value = from; value <= to; value += step) {
                values.set(value);
            }
        }
        // 只有裸 * 算"没限制"：0-59 与 * 等价，但它写得这么明白，多半是真的想限这个字段
        return new Parsed(values, !"*".equals(spec));
    }

    private static int number(String text, String name, String expression, String what) {
        try {
            return Integer.parseInt(text);
        }
        catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(name + "字段的" + what + "不是数字：" + text + "（" + expression + "）",
                    notANumber);
        }
    }

    private record Parsed(BitSet values, boolean restricted) {
    }
}
