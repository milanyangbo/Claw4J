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
 * 拼装的四条判据：正文、口径、时间、以及"时间每轮都在变"。
 *
 * <p>最后一条单独盯，是因为它是最容易犯而且最难发现的错：装配期算一次时间，
 * 跑一周之后提示词里还是启动那天，而所有"看起来对"的用例都会通过——
 * 因为它们都在同一个瞬间比对。
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
        assertTrue(prompt.contains("2026-11-03 20:28"), prompt);
        assertFalse(prompt.contains("部门口径"), "没配口径时不该出现这一节：" + prompt);
    }

    @Test
    @DisplayName("时间是『到分钟 + 星期 + 时区』：少了时区，晚上八点之后『今天』就已经差一天")
    void timeCarriesZoneAndWeekday() {
        assertEquals("2026-11-03 20:28（星期二，Asia/Shanghai）", PromptComposition.format(NOV_3));
    }

    @Test
    @DisplayName("时区跟着传入的时间走，不是系统默认时区——容器里那个通常是 UTC")
    void zoneComesFromTheGivenInstant() {
        ZonedDateTime utc = NOV_3.withZoneSameInstant(ZoneId.of("UTC"));

        assertEquals("2026-11-03 12:28（星期二，UTC）", PromptComposition.format(utc));
        assertTrue(PromptComposition.compose(persona("x"), List.of(), utc).contains("UTC"));
    }

    @Test
    @DisplayName("两轮不同时刻拼出来的提示词不同：时间必须是每轮算的，不能装配期固化")
    void timeIsRecomputedPerTurn() {
        String first = PromptComposition.compose(persona("你是销售助手。"), List.of(), NOV_3);
        String later = PromptComposition.compose(persona("你是销售助手。"), List.of(),
                NOV_3.plusMinutes(1));

        assertNotEquals(first, later);
        assertTrue(later.contains("2026-11-03 20:29"), later);
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
