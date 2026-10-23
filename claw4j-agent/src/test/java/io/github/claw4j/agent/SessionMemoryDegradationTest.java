package io.github.claw4j.agent;

import io.github.claw4j.agent.model.ScriptedChatModel;
import io.github.claw4j.agent.session.SessionMemoryConfiguration;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.tools.ToolsAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.session.autoconfigure.SessionServiceAutoConfiguration;
import org.springaicommunity.session.jdbc.autoconfigure.JdbcSessionRepositoryAutoConfiguration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话库不可用时，<b>记不住不能变成答不了</b>。
 *
 * <p>这是 {@code FaultTolerantSessionService} 存在的理由，也是它唯一的验收点：
 * 会话是增强能力，不是安全边界。库挂了的时候"没权限"这句话仍然要说出来——
 * 鉴权与脱敏失败只能拒绝，记忆失败只能降级，三种 Advisor 的失败姿势不一样。
 *
 * <p>不需要 Docker：数据源故意指向一个连不上的地址（端口 1 上不会有 Postgres）。
 */
class SessionMemoryDegradationTest {

    /** 端口 1 上什么都没有，连接会立刻被拒（不是超时，所以这条测试不慢）。 */
    private static final String UNREACHABLE = "jdbc:postgresql://127.0.0.1:1/claw4j";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AgentAutoConfiguration.class,
                    ToolsAutoConfiguration.class,
                    SessionMemoryConfiguration.class,
                    JdbcSessionRepositoryAutoConfiguration.class,
                    SessionServiceAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=" + UNREACHABLE,
                    "spring.datasource.username=claw4j",
                    "spring.datasource.password=claw4j",
                    // 必须与 application.yml 一致：starter 自带那个 schema initializer
                    // 在创建时就要连库探测驱动类型，不关掉的话"库挂了"会变成"应用起不来"
                    "spring.ai.session.repository.jdbc.initialize-schema=never")
            .withBean(DataSource.class, () -> new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    UNREACHABLE, "claw4j", "claw4j"))
            .withBean(JdbcClient.class, () -> JdbcClient.create(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(UNREACHABLE)))
            .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(UNREACHABLE)));

    @Test
    @DisplayName("会话库连不上时，这一轮照常回答（只是没有记忆）")
    void 库连不上时仍能回答() {
        ScriptedChatModel model = ScriptedChatModel.builder().thenText("你好，我能帮什么？").build();

        runner(model).run(context -> {
            String answer = context.getBean(AgentPort.class)
                    .reply(new InboundMessage(Principal.readOnly("ethan"), "http:t1", "你好"))
                    .text();

            assertThat(answer).isEqualTo("你好，我能帮什么？");
        });
    }

    /**
     * 这一条是关键：鉴权是安全边界，它不能因为"记不住"就一起失效。
     * 没有降级时这里会是一个 500，而 500 意味着"没权限"这句话根本没机会说出来。
     */
    @Test
    @DisplayName("会话库连不上时，鉴权照样拦得住")
    void 库连不上时鉴权仍然生效() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall("querySales", args("select 1"))
                .thenText("这句话不该被用上")
                .build();

        runner(model).run(context -> {
            // guest 读不到 sales_order（application.yml 里的角色表）
            Principal guest = Principal.of("guest", "guest", java.util.Set.of("region"),
                    java.util.Set.of("read"));

            String answer = context.getBean(AgentPort.class)
                    .reply(new InboundMessage(guest, "http:t1", "上周华东区成交额多少"))
                    .text();

            assertThat(answer).contains("没有权限");
        });
    }

    private static String args(String sql) {
        return "{\"sql\":\"" + sql + "\"}";
    }

    private ApplicationContextRunner runner(ScriptedChatModel model) {
        return contextRunner.withBean("chatModel", ChatModel.class, () -> model);
    }
}
