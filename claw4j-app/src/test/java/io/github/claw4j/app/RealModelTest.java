package io.github.claw4j.app;

import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 完整应用装配（app 模块那套接线）上的真实模型闭环。
 *
 * <p>它为什么曾经叫 {@code RealModelIT}、只在 {@code -Preal-model} 下跑：那时候回归基线
 * 全是假模型，真模型那趟烧 token、依赖外网、还依赖 {@code docker compose up} 起来的库，
 * 断一条就红，所以被隔离出日常回归。ADR-0011 之后**回归基线本身就是真模型**，
 * 这个隔离的理由就不成立了——留着它等于留一条"日常跑不到、想起来才跑"的裂缝，
 * 于是并回常规测试，去掉 IT 后缀与 profile。
 *
 * <p>它比 agent 侧那批真模型测试多覆盖的一层是**完整装配**：这里跑的是 app 的
 * {@code application.yml}、真的 Flyway 迁移、真的 fat-jar 级接线，而不是模块级装配。
 * 场景看着与 agent 侧重复，但断的东西不同——app 这一层断了（迁移没跑、某个自动配置
 * 在完整装配下不生效），agent 侧那些测试一条都不会红。
 *
 * <p>入口选 {@code AgentPort} 而不是 HTTP：HTTP 与 SSE 那层另有端到端测试覆盖，
 * 这一趟唯一 expensive 的部分是真实模型，没必要再叠一层传输。
 *
 * <p>断言刻意写得宽松：数字可能写成"128,400.00"也可能写成"128400"，前后还会有解释性文字。
 * 钉死整句只会让它在措辞变化时假红，而这里要抓的是"查不到数""说没权限"这类方向性错误。
 */
@SpringBootTest(properties = {
        // 默认 profile 关着迁移（为了没 Docker 也能启动），这一趟要有库
        "spring.flyway.enabled=true",
        // 主模型走运行时同一个开关：CLAW4J_MODEL_PRIMARY=qwen 就能用另一家跑同一批断言
        "claw4j.model.primary=${CLAW4J_MODEL_PRIMARY:deepseek}"
})
class RealModelTest {

    /** 上周华东区成交额，种子里就是 128400 = 54000 + 74400。 */
    private static final String[] EAST = {"128,400.00", "128400.00", "128400"};

    /** 上周华南区，06 号票"那华南呢"要拿到的那个。 */
    private static final String[] SOUTH = {"26,000.00", "26000.00", "26000"};

    /** 上周华北区。 */
    private static final String[] NORTH = {"31,500.00", "31500.00", "31500"};

    @Autowired
    private AgentPort port;

    /**
     * 每次跑用一个新用户：会话记忆是落库的，用同一个用户重跑会把上次的问答带进上下文，
     * 于是"这次跑的结果"取决于"上次跑过什么"。一次性证明不该有这种跨次依赖。
     */
    private static String freshUser() {
        return "it-" + System.currentTimeMillis();
    }

    @Test
    @DisplayName("同步：问一句话拿到库里那个数")
    void 同步闭环() {
        assertThat(port.reply(inbound(freshUser(), "admin", "上周华东区成交额多少")).text())
                .satisfies(answer -> containsOneOf(answer, EAST));
    }

    @Test
    @DisplayName("流式：同样的问题逐块吐出同一个数")
    void 流式闭环() {
        List<OutboundMessage> chunks = new ArrayList<>();
        port.stream(inbound(freshUser(), "admin", "上周华北区成交额多少"), chunks::add);

        assertThat(chunks.size()).isGreaterThan(1);
        String streamed = chunks.stream().map(OutboundMessage::text).reduce("", String::concat);
        containsOneOf(streamed, NORTH);
    }

    @Test
    @DisplayName("没有权限时拿不到数，而不是拿到一个空结果")
    void 越权角色拿不到数() {
        String answer = port.reply(inbound(freshUser(), "guest", "上周华东区成交额多少")).text();

        assertThat(answer).contains("没有权限");
        assertThat(answer).doesNotContain("128400").doesNotContain("128,400");
    }

    /**
     * 两轮对话：第二轮什么都不复述，只说"那华南呢"。拿得到华南的数，
     * 就说明上一轮的口径（上周、成交额、华东）真的被记住了——
     * 这是 06 号票在真实模型上的复验，假模型上验过不等于真模型接得上。
     */
    @Test
    @DisplayName("追问继承上一轮的口径")
    void 追问继承口径() {
        String user = freshUser();
        containsOneOf(port.reply(inbound(user, "admin", "上周华东区成交额多少")).text(), EAST);
        containsOneOf(port.reply(inbound(user, "admin", "那华南呢")).text(), SOUTH);
    }

    /**
     * 数字有几种写法（千分位、带不带小数），断言"出现其中之一"就够了。
     * 不用 AssertJ 的 containsAny 是因为它在这个版本上签名对不上数组，
     * 而这里需要的语义很窄，写清楚比绕 API 省事。
     */
    private static void containsOneOf(String actual, String[] expected) {
        for (String candidate : expected) {
            if (actual.contains(candidate)) {
                return;
            }
        }
        assertThat(actual).as("回答里应当出现 %s 之一，实际是：%s",
                        String.join(" / ", expected), actual)
                .contains(expected[0]);
    }

    private static InboundMessage inbound(String user, String role, String text) {
        Principal principal = "guest".equals(role)
                // 与 application.yml 里 claw4j.channel.http.roles.guest 同一口径：只能读 region
                ? Principal.of(user, "guest", Set.of("region"), Set.of("read"))
                : Principal.of(user, "admin", Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));
        return new InboundMessage(principal, "it:" + user, text, Instant.now(), null);
    }
}
