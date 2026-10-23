package io.github.claw4j.agent;

import io.github.claw4j.agent.model.ScriptedChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.tools.ToolsAutoConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 05 号票的正面判据：<b>问一句话，拿到库里算出来的数</b>。
 *
 * <p>与 {@code TextToSqlLoopTest} 的分工：那边数据源是替身，测的是循环；
 * 这边是真库、真工具、真自动配置，测的是"接在一起算得出对的数"。
 * 断言的落点是第二轮的提示词里带着 {@code 128400.00}——
 * 那个数是数据库算出来的，不是脚本编出来的（脚本只负责把它念出来）。
 *
 * <p>装配也走 {@code ToolsAutoConfiguration} 而不是手工塞 bean：
 * 这样"tools 的自动配置有没有把真实执行器挂上"这件事也被测到了，
 * 否则"bean 没注册、工具根本不在链上"这类问题要等到 app 里才暴露。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("postgresImageIsAvailable")
class SalesQueryEndToEndTest {

    /** 02 号票的种子契约：上周华东 = 54000（SO-LAST-01）+ 74400（SO-LAST-02）。 */
    private static final String LAST_WEEK_EAST = "128400.00";

    private static final String QUESTION = "上周华东区成交额多少";

    private static final String FINAL_ANSWER = "上周华东区成交额是 128400.00 元。";

    private static final String SQL = """
            select coalesce(sum(oi.amount), 0) as amount
            from business.sales_order o
                     join business.order_item oi on oi.order_id = o.id
            where o.region_code = 'east'
              and o.status <> 'CANCELLED'
              and o.order_date >= date_trunc('week', current_date) - interval '7 days'
              and o.order_date < date_trunc('week', current_date)""";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(image())
            .withDatabaseName("claw4j")
            .withUsername("claw4j")
            .withPassword("claw4j");

    private static DataSource dataSource;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;
    }

    @Test
    @DisplayName("问一句话拿到的是业务库算出来的数")
    void 同步路径拿到库里的数() {
        ScriptedChatModel model = scriptedModel();

        runner(model).run(context -> {
            OutboundMessage out = context.getBean(AgentPort.class).reply(ask());

            assertThat(model.promptTexts().get(1)).contains(LAST_WEEK_EAST);
            assertThat(out.text()).isEqualTo(FINAL_ANSWER);
        });
    }

    @Test
    @DisplayName("流式路径同样拿得到")
    void 流式路径拿到库里的数() {
        ScriptedChatModel model = scriptedModel();

        runner(model).run(context -> {
            List<OutboundMessage> chunks = new ArrayList<>();
            context.getBean(AgentPort.class).stream(ask(), chunks::add);

            assertThat(model.promptTexts().get(1)).contains(LAST_WEEK_EAST);
            assertThat(chunks.stream().map(OutboundMessage::text).reduce("", String::concat))
                    .isEqualTo(FINAL_ANSWER);
        });
    }

    /**
     * 数字必须是"数据库说的"，不是"脚本说的"。
     *
     * <p>把 SQL 换成一个必然为空的条件，第二轮的提示词里就不该再出现那个数——
     * 如果它还在，说明断言读到的是脚本写死的内容，整条测试一直是假绿。
     */
    @Test
    @DisplayName("换个条件数字就跟着变：证明那个数是查出来的")
    void 数字跟着查询条件变() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall("querySales", "{\"sql\":\"select 0 as amount where false\"}")
                .thenText(FINAL_ANSWER)
                .build();

        runner(model).run(context -> {
            context.getBean(AgentPort.class).reply(ask());

            assertThat(model.promptTexts().get(1))
                    .doesNotContain(LAST_WEEK_EAST)
                    .contains("0 行");
        });
    }

    private static ScriptedChatModel scriptedModel() {
        return ScriptedChatModel.builder()
                .thenToolCall("querySales", "{\"sql\":\"" + SQL.replace("\n", " ") + "\"}")
                .thenText(FINAL_ANSWER)
                .build();
    }

    private static InboundMessage ask() {
        return new InboundMessage(Principal.readOnly("ethan"), "http:t1", QUESTION);
    }

    private static ApplicationContextRunner runner(ScriptedChatModel model) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentAutoConfiguration.class, ToolsAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> model)
                // 同一个 DataSource 实例喂给 JdbcClient 与事务管理器：两个实例就是两条连接，
                // 只读事务与超时都会失效（细节见 JdbcReadOnlySqlExecutorTest）。
                .withBean(DataSource.class, () -> dataSource)
                .withBean(JdbcClient.class, () -> JdbcClient.create(dataSource))
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
    }

    private static String imageName() {
        String configured = System.getenv("CLAW4J_POSTGRES_IMAGE");
        return configured == null || configured.isBlank() ? "pgvector/pgvector:pg16" : configured;
    }

    /** 镜像站的名字不像 postgres，不声明兼容会被 Testcontainers 在启动前拒绝。 */
    private static DockerImageName image() {
        DockerImageName parsed = DockerImageName.parse(imageName());
        return parsed.getRepository().endsWith("postgres")
                ? parsed
                : parsed.asCompatibleSubstituteFor("postgres");
    }

    @SuppressWarnings("unused")
    static boolean postgresImageIsAvailable() {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(imageName()).exec();
            return true;
        }
        catch (RuntimeException notAvailable) {
            if ("required".equals(System.getProperty("claw4j.dbTests"))) {
                throw new IllegalStateException("数据库测试跑不起来（" + notAvailable.getMessage()
                        + "）。当前 claw4j.dbTests=required，所以这里不跳过。");
            }
            System.err.println("[SalesQueryEndToEndTest] 镜像不可用，本次跳过：" + notAvailable.getMessage());
            return false;
        }
    }
}
