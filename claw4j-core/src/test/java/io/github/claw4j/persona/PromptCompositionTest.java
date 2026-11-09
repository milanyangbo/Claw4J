package io.github.claw4j.persona;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拼装的几条判据：正文、口径、时间、数据范围，以及"同一天里时间不变"。
 *
 * <p>最后一条单独盯，是因为它同时是两件事的前提：时间必须是每轮算的（不能装配期固化），
 * 而粒度只能是天（分钟级会让以提示词为键的语义缓存每分钟失效一次）。
 */
class PromptCompositionTest {

    private static final ZonedDateTime NOV_3 = ZonedDateTime.of(
            2026, 11, 3, 20, 28, 0, 0, ZoneId.of("Asia/Shanghai"));

    private static Persona persona(String prompt) {
        return new Persona("sales", "销售助手", prompt, Optional.empty(), List.of());
    }

    @Test
    @DisplayName("没有人设之外的配置时，提示词就是正文加时间，不多不少")
    void plainPersonaGetsBodyAndTime() {
        String prompt = PromptComposition.compose(persona("你是销售助手。"), List.of(), NOV_3);

        assertTrue(prompt.startsWith("你是销售助手。"), prompt);
        assertTrue(prompt.contains("2026-11-03"), prompt);
        assertFalse(prompt.contains("部门口径"), "没配口径时不该出现这一节：" + prompt);
        assertFalse(prompt.contains("数据范围"), "没有身份时不该出现这一节：" + prompt);
    }

    @Test
    @DisplayName("时间是『到天 + 星期 + 时区』：少了时区，晚上八点之后『今天』就已经差一天")
    void timeCarriesZoneAndWeekday() {
        assertEquals("2026-11-03（星期二，Asia/Shanghai）", PromptComposition.format(NOV_3));
    }

    @Test
    @DisplayName("时区跟着传入的时间走，不是系统默认时区——容器里那个通常是 UTC")
    void zoneComesFromTheGivenInstant() {
        ZonedDateTime utc = NOV_3.withZoneSameInstant(ZoneId.of("UTC"));

        assertEquals("2026-11-03（星期二，UTC）", PromptComposition.format(utc));
        assertTrue(PromptComposition.compose(persona("x"), List.of(), utc).contains("UTC"));
    }

    @Test
    @DisplayName("同一天里换一轮，提示词不变：这是缓存能活过一分钟的前提")
    void sameDayKeepsThePromptStable() {
        String first = PromptComposition.compose(persona("你是销售助手。"), List.of(), NOV_3);
        String later = PromptComposition.compose(persona("你是销售助手。"), List.of(),
                NOV_3.plusMinutes(30));

        assertEquals(first, later,
                "提示词带分钟的话，框架算出的 contextHash 每分钟变一次，缓存活不过 60 秒");
    }

    @Test
    @DisplayName("跨天必须变：时间仍然是每轮算的，不是装配期固化")
    void nextDayChangesThePrompt() {
        String first = PromptComposition.compose(persona("你是销售助手。"), List.of(), NOV_3);
        String tomorrow = PromptComposition.compose(persona("你是销售助手。"), List.of(),
                NOV_3.plusDays(1));

        assertNotEquals(first, tomorrow);
        assertTrue(tomorrow.contains("2026-11-04"), tomorrow);
    }

    @Test
    @DisplayName("有身份才有数据范围：它是缓存区分权限的唯一入口")
    void dataScopeAppearsOnlyWhenGiven() {
        String anonymous = PromptComposition.compose(persona("你是销售助手。"), List.of(), NOV_3, null);
        String admin = PromptComposition.compose(persona("你是销售助手。"), List.of(), NOV_3, "fingerprint-a");
        String guest = PromptComposition.compose(persona("你是销售助手。"), List.of(), NOV_3, "fingerprint-b");

        assertFalse(anonymous.contains("数据范围"), anonymous);
        assertTrue(admin.contains("数据范围"), admin);
        assertTrue(admin.contains("fingerprint-a"), admin);
        assertNotEquals(admin, guest,
                "权限不同 → 提示词不同 → contextHash 不同 → 缓存不互相命中");
    }

    @Test
    @DisplayName("口径按声明的顺序拼进来，每份都带出处——出问题时能顺着这一行回到源头")
    void glossaryCarriesSourcesInOrder() {
        String prompt = PromptComposition.compose(persona("你是销售助手。"), List.of(
                new GlossarySection("glossary/sales.md", "成交额 = sum(amount)"),
                new GlossarySection("glossary/time.md", "上周 = 上一自然周")), NOV_3);

        int sales = prompt.indexOf("glossary/sales.md");
        int time = prompt.indexOf("glossary/time.md");
        assertTrue(sales > 0 && time > sales, "两处出处都要在，且按声明顺序：" + prompt);
        assertTrue(prompt.contains("成交额 = sum(amount)"), prompt);
        assertTrue(prompt.contains("## 部门口径"), prompt);
    }

    @Test
    @DisplayName("正文首尾的空白被去掉：它来自文件，末尾常带换行")
    void bodyIsStripped() {
        String prompt = PromptComposition.compose(persona("\n\n  你是销售助手。 \n\n"), List.of(), NOV_3);

        assertTrue(prompt.startsWith("你是销售助手。"), prompt);
    }

    @Test
    @DisplayName("缺少任何一样都当场失败，不拼出半份提示词")
    void missingPartsFail() {
        assertThrows(NullPointerException.class,
                () -> PromptComposition.compose(null, List.of(), NOV_3));
        assertThrows(NullPointerException.class,
                () -> PromptComposition.compose(persona("x"), null, NOV_3));
        assertThrows(NullPointerException.class,
                () -> PromptComposition.compose(persona("x"), List.of(), null));
    }
}
