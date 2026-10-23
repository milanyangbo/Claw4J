package io.github.claw4j.agent;

import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.agent.session.SessionMemoryConfiguration;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import io.github.claw4j.tools.ToolsAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话库连不上时的降级（ADR-0005）：记不住可以，**不能一起把鉴权也丢了**。
 *
 * <p>数据源故意指向一个连不上的地址。两条判据：这一轮照常回答（只是没有记忆）、
 * 鉴权照样拦得住。第二条是关键——没有降级时这里会是一个 500，
 * 而 500 意味着"没权限"这句话根本没机会说出来。
 *
 * <p>换真模型之后（ADR-0011）断言改成方向性：回答非空 / 回答里有那句明确的拒绝。
 * 鉴权那条带两次重试，因为它需要模型真的发起一次工具调用——这一步由模型决定，
 * 不是我们能强制的（好在"问成交额"这个意图足够明确）。
 */
class SessionMemoryDegradationTest {

    /** 一个没人听的地址：连不上，而且连不上得够快（本地回环，不靠超时）。 */
    private static final String UNREACHABLE =
            "jdbc:postgresql://127.0.0.1:1/nobody?connectTimeout=2";

    /**
     * 连接信息走 {@code spring.datasource.*} 属性而不是塞一个 {@code DataSource} bean：
     * 会话那一侧的 repository 是 Boot 按属性装配的，只给 bean 的话它会在装配期就
     * "Failed to determine a suitable driver class" 而失败——那就不是在测降级，
     * 而是连启动都没到。连不上必须发生在**用**的时候。
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AgentAutoConfiguration.class,
                    ToolsAutoConfiguration.class,
                    SessionMemoryConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=" + UNREACHABLE,
                    "spring.datasource.username=nobody",
                    "spring.datasource.password=nobody",
                    "spring.ai.session.repository.jdbc.initialize-schema=never")
            // 工具要注册得上（鉴权那一条需要模型真的发起一次 querySales），
            // 但注册不等于能连：底下这个 DataSource 指向没人听的端口
            .withBean(JdbcClient.class, () -> JdbcClient.create(unreachable()))
            .withBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(unreachable()));

    @Test
    @DisplayName("会话库连不上时，这一轮照常回答（只是没有记忆）")
    void 库连不上时仍能回答() {
        runner().run(context -> RealModelSupport.eventually(2, "没记忆也照常回答", () -> {
            String answer = context.getBean(AgentPort.class)
                    .reply(new InboundMessage(Principal.readOnly("ethan"), "http:t1", "你好"))
                    .text();

            assertThat(answer).isNotBlank();
            return answer;
        }));
    }

    /**
     * 这一条是关键：鉴权是安全边界，它不能因为"记不住"就一起失效。
     * 没有降级时这里会是一个 500，而 500 意味着"没权限"这句话根本没机会说出来。
     */
    @Test
    @DisplayName("会话库连不上时，鉴权照样拦得住")
    void 库连不上时鉴权仍然生效() {
        runner().run(context -> RealModelSupport.eventually(2, "没记忆时鉴权仍拦得住", () -> {
            // guest 读不到 sales_order（application.yml 里的角色表）
            Principal guest = Principal.of("guest", "guest", Set.of("region"), Set.of("read"));

            String answer = context.getBean(AgentPort.class)
                    .reply(new InboundMessage(guest, "http:t1", "上周华东区成交额多少"))
                    .text();

            assertThat(answer).contains("没有权限");
            return answer;
        }));
    }

    private ApplicationContextRunner runner() {
        return contextRunner.withBean("chatModel", ChatModel.class, RealModelSupport::chatModel);
    }

    private static org.springframework.jdbc.datasource.DriverManagerDataSource unreachable() {
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(UNREACHABLE);
    }
}
