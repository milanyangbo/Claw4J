package io.github.claw4j.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 句柄值得有的性质：形状可校验、出口才换、报错不带值。
 *
 * <p>没有一条用例去连模型或起进程——这个类本来就只做"这串配置是什么"，
 * 出口那几处各自有用例（{@code ModelConfiguration} 与 {@code ProcessStdioMcpEndpoint}）。
 */
class SecretValueTest {

    private static final String WHERE = "claw4j.model.deepseek.api-key";

    /** 一份够用的假环境。真实实现在 capability 里，这里只要"能答出有没有"。 */
    private static SecretResolver env(Map<String, String> values) {
        return name -> Optional.ofNullable(values.get(name));
    }

    @Test
    @DisplayName("句柄：原文就是名字，句柄名被记下来")
    void handleKeepsOnlyTheName() {
        SecretValue value = SecretValue.parse("secret:deepseek_api_key", WHERE, false);

        assertEquals("secret:deepseek_api_key", value.text());
        assertEquals(List.of("deepseek_api_key"), value.handles());
        assertFalse(value.isAbsent());
    }

    @Test
    @DisplayName("模板：前后缀保留，句柄照样被认出来")
    void templateWithPrefixKeepsBoth() {
        SecretValue value = SecretValue.parse("Bearer secret:deepwiki_token", "headers.Authorization", false);

        assertEquals(List.of("deepwiki_token"), value.handles());
        assertEquals("Bearer real-token",
                value.render(env(Map.of("deepwiki_token", "real-token"))));
    }

    @Test
    @DisplayName("一个值里两个句柄：各自替换，顺序不乱")
    void twoHandlesInOneValue() {
        SecretValue value = SecretValue.parse("secret:a secret:b", "where", false);

        assertEquals(List.of("a", "b"), value.handles());
        assertEquals("1 2", value.render(env(Map.of("a", "1", "b", "2"))));
    }

    @Test
    @DisplayName("显式常量：只有非凭据位接受，前缀被剥掉")
    void plainIsAcceptedOnlyWhereAllowed() {
        SecretValue value = SecretValue.parse("plain:/tmp/isolated", "env.HOME", true);

        assertTrue(value.handles().isEmpty());
        assertEquals("/tmp/isolated", value.render(env(Map.of())));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SecretValue.parse("plain:/tmp/isolated", WHERE, false));
        assertTrue(refused.getMessage().contains(WHERE), refused.getMessage());
        assertTrue(refused.getMessage().contains("句柄"), refused.getMessage());
    }

    @Test
    @DisplayName("凭据位上裸写明文：失败，且报错里没有那串明文")
    void plainCredentialIsRefusedWithoutEchoingIt() {
        String plaintext = "sk-live-0123456789abcdef";

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SecretValue.parse(plaintext, WHERE, false));

        assertTrue(refused.getMessage().contains(WHERE), refused.getMessage());
        assertFalse(refused.getMessage().contains("0123456789"), refused.getMessage());
    }

    @Test
    @DisplayName("句柄名大写：失败，并说清它是环境变量名的小写写法")
    void uppercaseHandleIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SecretValue.parse("secret:DEEPSEEK_API_KEY", WHERE, false));

        assertTrue(refused.getMessage().contains("小写"), refused.getMessage());
        assertTrue(refused.getMessage().contains("DEEPSEEK_API_KEY"), refused.getMessage());
    }

    @Test
    @DisplayName("没配与配了空串是同一件事")
    void blankIsAbsent() {
        assertTrue(SecretValue.parse(null, WHERE, false).isAbsent());
        assertTrue(SecretValue.parse("   ", WHERE, false).isAbsent());
        assertEquals("", SecretValue.parse("", WHERE, false).render(env(Map.of())));
    }

    @Test
    @DisplayName("缺真值：报错带句柄名与变量名，不带值也不带片段")
    void missingSecretNamesTheHandleOnly() {
        SecretValue value = SecretValue.parse("secret:deepwiki_token", "headers.Authorization", false);

        MissingSecretException missing = assertThrows(MissingSecretException.class,
                () -> value.render(env(Map.of())));

        assertEquals("secret:deepwiki_token", missing.handle());
        assertTrue(missing.getMessage().contains("DEEPWIKI_TOKEN"), missing.getMessage());
        assertTrue(missing.getMessage().contains("headers.Authorization"), missing.getMessage());
    }

    @Test
    @DisplayName("启动期探测只问在不在，不取也不存")
    void requireResolvableAsksWithoutKeeping() {
        AtomicInteger asked = new AtomicInteger();
        SecretResolver counting = name -> {
            asked.incrementAndGet();
            return Optional.of("value");
        };

        SecretValue.parse("secret:a secret:b", "where", false).requireResolvable(counting);

        assertEquals(2, asked.get());
    }

    @Test
    @DisplayName("toString 给原文：句柄不会因为顺手打印而变成真值")
    void toStringStaysTheHandle() {
        assertEquals("secret:deepseek_api_key",
                SecretValue.parse("secret:deepseek_api_key", WHERE, false).toString());
    }

    @Test
    @DisplayName("句柄名到环境变量名的换算：转大写，点与横线变下划线")
    void environmentNameIsOneRule() {
        assertEquals("DEEPSEEK_API_KEY", SecretResolver.environmentName("deepseek_api_key"));
        assertEquals("A_B_C", SecretResolver.environmentName("a.b-c"));
    }
}
