package io.github.claw4j.agent.session;

import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话 id 的派生规则。"chatId 不当会话主键"这条约束的可执行版本。
 */
class SessionKeysTest {

    private static final String CHAT_ID = "http:t1";

    /**
     * 会话 id 的输入里没有 chatId，所以"通道换了"这件事根本传不进来。
     * 这条断言钉的是确定性：同一个人在两次调用里拿到同一个 id，
     * 里面不含时间、随机数或通道痕迹。跨通道真的不失忆，由
     * {@code SessionMemoryEndToEndTest} 用两个不同的 chatId 走一遍端口来证明。
     */
    @Test
    @DisplayName("会话 id 是确定的：同一个用户、两次调用、同一个值")
    void 会话标识是确定的() {
        Principal ethan = Principal.readOnly("ethan");

        String first = SessionKeys.sessionIdOf(ethan);

        assertThat(first).isEqualTo(SessionKeys.sessionIdOf(ethan))
                .isEqualTo(SessionKeys.PREFIX + "ethan")
                .doesNotContain(CHAT_ID);
    }

    @Test
    @DisplayName("会话 id 不等于 chatId：通道侧寻址键没有混进主键")
    void 会话主键不是通道寻址键() {
        String sessionId = SessionKeys.sessionIdOf(Principal.readOnly("ethan"));

        assertThat(sessionId).isNotEqualTo(CHAT_ID);
        assertThat(sessionId).startsWith(SessionKeys.PREFIX);
    }

    @Test
    @DisplayName("两个人不是同一个会话")
    void 换个用户换个会话() {
        assertThat(SessionKeys.sessionIdOf(Principal.readOnly("ethan")))
                .isNotEqualTo(SessionKeys.sessionIdOf(Principal.readOnly("lisa")));
    }

    /**
     * 空 userId 的后果不是"存不下"，而是所有人共用一个会话：
     * 看起来记得住，记得是别人的。所以这里直接拒绝，而不是回落到某个默认值。
     */
    @Test
    @DisplayName("userId 为空时拒绝，而不是让所有人共用一个会话")
    void 空用户标识被拒() {
        assertThatThrownBy(() -> SessionKeys.sessionIdOf(Principal.readOnly("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }
}
