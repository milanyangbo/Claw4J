package io.github.claw4j.agent;

import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.capability.ToolsAutoConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 05 号票的正面判据：<b>问一句话，拿到库里算出来的数</b>——而且这回是真模型自己去查。
 *
 * <p><b>这个数现在是模型查出来的，不是脚本念出来的。</b>改真模型之前（ADR-0011），
 * 断言落在"第二轮提示词里带着 {@code 128400.00}"，而那个数是脚本里的常量：
 * 它证明的是"链路能把一串字符传下去"，不是"模型会按工具描述去调 {@code querySales}"。
 * 现在断言落在客户端拿到的回答上，中间的 SQL 由模型自己写。
 *
 * <p>装配仍然走 {@code ToolsAutoConfiguration} 而不是手工塞工具 bean：
 * 这样"tools 的自动配置有没有把真实执行器挂上"这件事也被测到了。
 * 模型换成 {@link RealModelSupport#chatModel()}——真 DeepSeek，温度 0。
 *
 * <p>断言只写方向性：数字可能写成"128,400.00"也可能写成"128400"，前后还会有解释性文字。
 * 钉整句只会让它在措辞变化时假红，而这里要抓的是"查不到数""编了一个数"这类方向性错误。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("postgresImageIsAvailable")
class SalesQueryEndToEndTest {

    /** 02 号票的种子契约：上周华东 = 54000（SO-LAST-01）+ 74400（SO-LAST-02）。 */
    private static final String EAST = "128400";

    /** 上周华北区。 */
    private static final String NORTH = "31500";

    private static DataSource dataSource;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.container();

    @BeforeAll
    static void migrate() {
        PostgresSupport.migrate(POSTGRES);
        dataSource = PostgresSupport.dataSource(POSTGRES);
    }

    @Test
    @DisplayName("问一句话拿到的是业务库算出来的数")
    void 同步路径拿到库里的数() {
        runner().run(context -> RealModelSupport.eventually(2, "同步拿到华东的数", () -> {
            OutboundMessage out = context.getBean(AgentPort.class).reply(ask("ethan", "上周华东区成交额多少"));
            RealModelSupport.assertContainsAmount(out.text(), EAST);
            return out.text();
        }));
    }

    @Test
    @DisplayName("流式路径同样拿得到")
    void 流式路径拿到库里的数() {
        runner().run(context -> RealModelSupport.eventually(2, "流式拿到华北的数", () -> {
            List<OutboundMessage> chunks = new ArrayList<>();
            context.getBean(AgentPort.class).stream(ask("ethan", "上周华北区成交额多少"), chunks::add);

            // 块数大于 1 是流式没被静默降级成"整段一次性返回"的判据
            assertThat(chunks.size()).isGreaterThan(1);
            String streamed = chunks.stream().map(OutboundMessage::text).reduce("", String::concat);
            RealModelSupport.assertContainsAmount(streamed, NORTH);
            assertThat(chunks.getLast().finalChunk()).isTrue();
            return streamed;
        }));
    }

    /**
     * 数字必须是"数据库说的"，不是"模型记住的"。
     *
     * <p>换一个问题，数就得跟着换。这条是整套断言的证伪判据：
     * 如果模型在编数（或者把上一轮的数带过来），两次回答里会出现同一个数，
     * 而真库里华东与华北是两个不同的数。换个用户问，避免会话记忆把上一轮带进来。
     */
    @Test
    @DisplayName("换个问题数字就跟着变：证明那个数是查出来的")
    void 数字跟着查询条件变() {
        runner().run(context -> {
            String east = RealModelSupport.eventually(2, "华东的数", () -> {
                String answer = context.getBean(AgentPort.class)
                        .reply(ask("east-user", "上周华东区成交额多少")).text();
                RealModelSupport.assertContainsAmount(answer, EAST);
                return answer;
            });
            String north = RealModelSupport.eventually(2, "华北的数", () -> {
                String answer = context.getBean(AgentPort.class)
                        .reply(ask("north-user", "上周华北区成交额多少")).text();
                RealModelSupport.assertContainsAmount(answer, NORTH);
                return answer;
            });

            RealModelSupport.assertDoesNotContainAmount(east, NORTH);
            RealModelSupport.assertDoesNotContainAmount(north, EAST);
        });
    }

    private static InboundMessage ask(String user, String question) {
        return new InboundMessage(Principal.readOnly(user), "http:t1", question);
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentAutoConfiguration.class, ToolsAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, RealModelSupport::chatModel)
                // 同一个 DataSource 实例喂给 JdbcClient 与事务管理器：两个实例就是两条连接，
                // 只读事务与超时都会失效（细节见 JdbcReadOnlySqlExecutorTest）。
                .withBean(DataSource.class, () -> dataSource)
                .withBean(JdbcClient.class, () -> JdbcClient.create(dataSource))
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
    }

    @SuppressWarnings("unused")
    static boolean postgresImageIsAvailable() {
        return PostgresSupport.databaseAvailable("SalesQueryEndToEndTest");
    }
}
