package io.github.claw4j.example.mcp.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * cron 求解的判定：解得对、解得出来的时间真的在往后、写不通顺的表达式当场拒。
 *
 * <p>基准时刻固定为 2026-11-16 星期一 10:30（Asia/Shanghai）：星期几是断言的一部分，
 * 用 {@code now()} 就变成"哪天跑哪天红"。
 */
class CronScheduleTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 2026-11-16 是星期一。 */
    private static final Instant MONDAY_1030 = ZonedDateTime.of(2026, 11, 16, 10, 30, 0, 0, ZONE).toInstant();

    @Test
    @DisplayName("常见写法都解得出来：*/n、工作日范围、逢整点，星期 0 与 7 都是周日")
    void parsesTheFiveFieldForms() {
        assertThat(CronSchedule.parse("*/15 * * * *").next(ZONE, MONDAY_1030, 3))
                .as("每 15 分钟：10:30 之后是 10:45、11:00、11:15")
                .containsExactly(at(2026, 11, 16, 10, 45), at(2026, 11, 16, 11, 0), at(2026, 11, 16, 11, 15));

        assertThat(CronSchedule.parse("0 9 * * 1-5").next(ZONE, MONDAY_1030, 2))
                .as("工作日 9 点：周一的 9 点已经过去，下一次是周二")
                .containsExactly(at(2026, 11, 17, 9, 0), at(2026, 11, 18, 9, 0));

        assertThat(CronSchedule.parse("0 9 * * 0").next(ZONE, MONDAY_1030, 1))
                .as("0 与 7 指的是同一个周日：两个写法解出来的时间必须一样")
                .isEqualTo(CronSchedule.parse("0 9 * * 7").next(ZONE, MONDAY_1030, 1))
                .containsExactly(at(2026, 11, 22, 9, 0));
    }

    @Test
    @DisplayName("日与周同时限制直接拒绝：不假装 Unix 那种并集语义")
    void refusesDayAndWeekdayTogether() {
        assertThatThrownBy(() -> CronSchedule.parse("0 0 1 * 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("日与周不能同时限制");
    }

    @Test
    @DisplayName("取到的时间严格递增、都在给的时刻之后；写不成的表达式当场拒")
    void nextRunsAreStrictlyIncreasing() {
        List<LocalDateTime> runs = CronSchedule.parse("0 2 * * *").next(ZONE, MONDAY_1030, 5);

        assertThat(runs).hasSize(5).isSortedAccordingTo(LocalDateTime::compareTo);
        assertThat(runs.getFirst()).isAfter(LocalDateTime.of(2026, 11, 16, 10, 30));

        assertThatThrownBy(() -> CronSchedule.parse("61 * * * *"))
                .as("分钟越界要当场说清，而不是解出一个空表")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分字段");
        assertThatThrownBy(() -> CronSchedule.parse("0 9 * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 个字段");
    }

    private static LocalDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute);
    }
}
