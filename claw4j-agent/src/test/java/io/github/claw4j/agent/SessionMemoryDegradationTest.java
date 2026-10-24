package io.github.claw4j.agent;

import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.agent.session.SessionMemoryConfiguration;
import io.github.claw4j.capability.ToolsAutoConfiguration;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话库连不上时的降级（ADR-0005）：记不住可以，<b>不能连"没权限"这句话都说不出来</b>。
 *
 * <p>这一条与 {@code session.SessionMemoryTest} 里那几条降级不重复：那几条证明的是
 * {@code FaultTolerantSessionService} 自己会把存储故障吃掉，这一条证明的是
 * <b>装配里确实包了它</b>——把它从 {@link SessionMemoryConfiguration} 摘掉，
 * 单元测试全绿，这里红。判据是"整条链还能不能答话"，只有装配起来才看得见。
 *
 * <p>数据源故意指向一个没人听的地址。连不上必须发生在<b>用</b>的时候，不能发生在装配的时候：
 * 所以连接信息走 {@code spring.datasource.*} 属性，而不是塞一个连不上的 bean——
 * 后者会让 Boot 在装配期就报 "Failed to determine a suitable driver class"，根本到不了降级那一段。
 */
class SessionMemoryDegradationTest {

    /** 一个没人听的地址：本地回环的 1 号端口，失败得快，不靠超时。 */
    private static final String UNREACHABLE =
            "jdbc:postgresql://127.0.0.1:1/nobody?connectTimeout=2";

    private static final DriverManagerDataSource UNREACHABLE_DATASOURCE =
            new DriverManagerDataSource(UNREACHABLE);

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
            // 工具要注册得上（鉴权那一段需要模型真的发起一次 querySales），
            // 但注册不等于连得上：底下这个 DataSource 指向没人听的端口
            .withBean(JdbcClient.class, () -> JdbcClient.create(UNREACHABLE_DATASOURCE))
            .withBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(UNREACHABLE_DATASOURCE));

    /**
     * 两句断言钉住同一件事的两面：
     * <ol>
     *   <li>库挂了这一轮照常回答（只是没有记忆），不是 500；</li>
     *   <li>越权仍然被拒（{@code guest} 读不到 {@code sales_order}）——
     *       降级只降记忆，不能顺手把安全边界也降掉。而 500 之所以不可接受，
     *       正是因为 500 里"没权限"这句话根本没机会说出来。</li>
     * </ol>
     */
    @Test
    @DisplayName("会话库连不上时：这一轮照常回答，且越权照样被拒")
    void 库连不上时照常回答且鉴权不失效() {
        contextRunner.withBean("chatModel", ChatModel.class, RealModelSupport::chatModel)
                .run(context -> RealModelSupport.eventually(2, "没记忆也能答，且越权仍被拒", () -> {
                    AgentPort agent = context.getBean(AgentPort.class);

                    String answer = agent.reply(new InboundMessage(
                            Principal.readOnly("ethan"), "http:t1", "你好")).text();
                    assertThat(answer).isNotBlank();

                    // guest 读不到 sales_order（application.yml 里的角色表）
                    Principal guest = Principal.of("guest", "guest", Set.of("region"), Set.of("read"));
                    String refused = agent.reply(new InboundMessage(
                            guest, "http:t1", "上周华东区成交额多少")).text();
                    assertThat(refused).contains("没有权限");
                    return refused;
                }));
    }
}
