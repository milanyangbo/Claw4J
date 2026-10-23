package io.github.claw4j.agent;

import io.github.claw4j.agent.model.ScriptedChatModel;
import io.github.claw4j.agent.session.SessionMemoryConfiguration;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.tools.ToolsAutoConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springaicommunity.session.autoconfigure.SessionServiceAutoConfiguration;
import org.springaicommunity.session.jdbc.autoconfigure.JdbcSessionRepositoryAutoConfiguration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 06 号票的正面判据：<b>第二轮接着第一轮</b>。
 *
 * <p>判据必须能证伪，所以模型的第二轮回答不是一个写死的字符串，而是一个函数：
 * 提示词里看得见上一轮（"华东""上周"）才答得出华南的数，看不见就只能反问。
 * 于是"有记忆"和"没记忆"落在两个不同的分支上——无记忆的实现会答出反问那一句，断言立刻红。
 * 反过来，断言写成"第二轮有回答"是不成立的：随便一个实现都有回答。
 *
 * <p>会话存储是真的 JDBC（同一个 Postgres、另一个 schema），不是替身：
 * "多实例不失忆"是 ADR-0005 选 JDBC 的全部理由，用替身等于把要证明的东西替掉了。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("postgresImageIsAvailable")
class SessionMemoryEndToEndTest {

    /** 02 号票的种子契约：上周华东 128400，上周华南 26000。 */
    private static final String EAST_AMOUNT = "128400.00";

    private static final String SOUTH_AMOUNT = "26000.00";

    private static final String FIRST_QUESTION = "上周华东区成交额多少";

    private static final String FOLLOW_UP = "那华南呢";

    private static final String FIRST_ANSWER = "华东上周成交额是 128400.00 元。";

    /** 看得见上一轮才答得出来；看不见就只能反问——这一句是整条判据的支点。 */
    private static final String FORGOTTEN_ANSWER = "请问您说的是哪个区、哪个时间段？";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.container();

    private static DataSource dataSource;

    @BeforeAll
    static void migrate() {
        PostgresSupport.migrate(POSTGRES);
        dataSource = PostgresSupport.dataSource(POSTGRES);
    }

    @Test
    @DisplayName("第二轮接着第一轮：不用把“上周”“成交额”重讲一遍")
    void 同步路径的追问接得上() {
        ScriptedChatModel model = twoTurnModel(FIRST_ANSWER);

        runner(model).run(context -> {
            AgentPort agent = context.getBean(AgentPort.class);
            InboundMessage first = new InboundMessage(Principal.readOnly("ethan"), "http:t1", FIRST_QUESTION);
            InboundMessage second = new InboundMessage(Principal.readOnly("ethan"), "http:t1", FOLLOW_UP);

            assertThat(agent.reply(first).text()).isEqualTo(FIRST_ANSWER);

            OutboundMessage out = agent.reply(second);

            // 判据：算的是华南，不是"又算了一遍华东"，也不是"反问用户"
            assertThat(out.text()).contains(SOUTH_AMOUNT).doesNotContain(FORGOTTEN_ANSWER);
            // 继承的是带口径的历史：上一轮的问句与"上周"都在第二轮提示词里
            assertThat(model.promptTexts().getLast())
                    .contains(FIRST_QUESTION)
                    .contains(FIRST_ANSWER);
        });
    }

    @Test
    @DisplayName("流式路径同样接得上")
    void 流式路径的追问接得上() {
        ScriptedChatModel model = twoTurnModel(FIRST_ANSWER);

        runner(model).run(context -> {
            AgentPort agent = context.getBean(AgentPort.class);
            agent.reply(new InboundMessage(Principal.readOnly("ethan"), "http:t1", FIRST_QUESTION));

            List<OutboundMessage> chunks = new ArrayList<>();
            agent.stream(new InboundMessage(Principal.readOnly("ethan"), "http:t1", FOLLOW_UP), chunks::add);

            assertThat(chunks.stream().map(OutboundMessage::text).reduce("", String::concat))
                    .contains(SOUTH_AMOUNT)
                    .doesNotContain(FORGOTTEN_ANSWER);
            assertThat(chunks.getLast().finalChunk()).isTrue();
        });
    }

    /**
     * chatId 是通道侧的寻址键，不是会话主键：换一条通道进来，上下文还在。
     * 反过来（把 chatId 当主键）会让这条测试红在第二轮的提示词上。
     */
    @Test
    @DisplayName("换一条通道进来，上下文仍然连续")
    void 换通道不失忆() {
        ScriptedChatModel model = twoTurnModel(FIRST_ANSWER);

        runner(model).run(context -> {
            AgentPort agent = context.getBean(AgentPort.class);
            agent.reply(new InboundMessage(Principal.readOnly("ethan"), "http:t1", FIRST_QUESTION));

            // 同一个人在飞书那边接着问：chatId 换了，会话没换
            OutboundMessage out = agent.reply(
                    new InboundMessage(Principal.readOnly("ethan"), "feishu:oc_abc", FOLLOW_UP));

            assertThat(out.text()).contains(SOUTH_AMOUNT).doesNotContain(FORGOTTEN_ANSWER);
        });
    }

    /**
     * 会话按人隔离：换个用户就是另一个会话。
     * 少了这条，"全局一份记忆"这种实现也能绿着通过前面几条。
     */
    @Test
    @DisplayName("换一个人是干净的会话，不会串到别人的上下文")
    void 换个用户不串味() {
        ScriptedChatModel model = twoTurnModel(FIRST_ANSWER);

        runner(model).run(context -> {
            AgentPort agent = context.getBean(AgentPort.class);
            agent.reply(new InboundMessage(Principal.readOnly("ethan"), "http:t1", FIRST_QUESTION));

            OutboundMessage out = agent.reply(
                    new InboundMessage(Principal.readOnly("lisa"), "http:t2", FOLLOW_UP));

            assertThat(out.text()).isEqualTo(FORGOTTEN_ANSWER).doesNotContain(SOUTH_AMOUNT);
        });
    }

    /**
     * "多实例不失忆"是 ADR-0005 选 JDBC 的全部理由：两个上下文就是两个实例，
     * 它们不共享任何内存，只共享那张表。
     */
    @Test
    @DisplayName("两个应用实例之间记得住：记忆不在进程内存里")
    void 多实例之间记得住() {
        ScriptedChatModel firstInstanceModel = ScriptedChatModel.builder()
                .thenToolCall("querySales", args(eastSql()))
                .thenText(FIRST_ANSWER)
                .build();
        ScriptedChatModel secondInstanceModel = ScriptedChatModel.builder()
                .thenToolCall("querySales", args(southSql()))
                .thenAnswer(prompt -> prompt.contains("华东") ? "华南上周成交额是 " + SOUTH_AMOUNT + " 元"
                        : FORGOTTEN_ANSWER)
                .build();

        runner(firstInstanceModel).run(first -> {
            first.getBean(AgentPort.class)
                    .reply(new InboundMessage(Principal.readOnly("ethan"), "http:t1", FIRST_QUESTION));
        });

        // 第二个实例：全新的容器、全新的连接池，只有一个 shared 的 Postgres
        runner(secondInstanceModel).run(second -> {
            OutboundMessage out = second.getBean(AgentPort.class)
                    .reply(new InboundMessage(Principal.readOnly("ethan"), "http:t1", FOLLOW_UP));

            assertThat(out.text()).contains(SOUTH_AMOUNT).doesNotContain(FORGOTTEN_ANSWER);
        });
    }

    /**
     * 会话表与业务表同库不同 schema（ADR-0005）。
     * 只断言"表存在"是不够的：官方自动配置那份 repository 会把表建在 public，
     * 而 public 和业务库都在，所以这里连"business 里没有会话表"一起断言。
     */
    @Test
    @DisplayName("会话落在自己的 schema，没有混进业务 schema")
    void 会话表在自己的schema() {
        ScriptedChatModel model = twoTurnModel(FIRST_ANSWER);

        runner(model).run(context -> {
            context.getBean(AgentPort.class)
                    .reply(new InboundMessage(Principal.readOnly("ethan"), "http:t1", FIRST_QUESTION));

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("""
                    select count(*) from information_schema.tables
                    where table_schema = 'claw4j_session'
                      and table_name in ('ai_session', 'ai_session_event')""", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    select count(*) from information_schema.tables
                    where table_schema in ('business', 'public')
                      and table_name in ('ai_session', 'ai_session_event')""", Integer.class))
                    .isZero();
            // 会话真的写进去了：不是一个空壳 schema
            assertThat(jdbc.queryForObject("select count(*) from claw4j_session.ai_session", Integer.class))
                    .isPositive();
            assertThat(jdbc.queryForList(
                    "select message_content from claw4j_session.ai_session_event", String.class))
                    .anyMatch(content -> content != null && content.contains(FIRST_ANSWER));
        });
    }

    /**
     * 会话里存的是脱敏之后的文本，不是原始号码。
     *
     * <p>出站脱敏了但会话表里躺着明文，是比出站更持久的一份泄露，而且没人会去看。
     * 这一条钉的是链的收尾顺序：脱敏先收尾，记忆后收尾（见
     * {@link SessionMemoryConfiguration#ADVISOR_ORDER}）。
     */
    @Test
    @DisplayName("会话里存的是掩码后的文本，不是原始手机号")
    void 会话里存的是脱敏后的文本() {
        String withPhone = "华东上周成交额是 128400.00 元，对接人 13800138000。";
        ScriptedChatModel model = twoTurnModel(withPhone);

        runner(model).run(context -> {
            OutboundMessage out = context.getBean(AgentPort.class)
                    .reply(new InboundMessage(Principal.readOnly("ethan"), "http:t1", FIRST_QUESTION));

            // 出站是掩码的（04 号票的行为，这里只是顺带确认同一条链还在工作）
            assertThat(out.text()).contains("138****8000").doesNotContain("13800138000");

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            List<String> stored = jdbc.queryForList(
                    "select message_content from claw4j_session.ai_session_event", String.class);

            assertThat(stored).anyMatch(content -> content != null && content.contains("138****8000"));
            assertThat(stored).noneMatch(content -> content != null && content.contains("13800138000"));
        });
    }

    private static ScriptedChatModel twoTurnModel(String firstAnswer) {
        return ScriptedChatModel.builder()
                .thenToolCall("querySales", args(eastSql()))
                .thenText(firstAnswer)
                .thenToolCall("querySales", args(southSql()))
                .thenAnswer(prompt -> prompt.contains("华东")
                        ? "华南上周成交额是 " + SOUTH_AMOUNT + " 元"
                        : FORGOTTEN_ANSWER)
                .build();
    }

    private static String args(String sql) {
        return "{\"sql\":\"" + sql + "\"}";
    }

    private static String eastSql() {
        return lastWeekSql("east");
    }

    private static String southSql() {
        return lastWeekSql("south");
    }

    /** 时间窗与口径照抄 05 号票那条：区域是唯一的变量。 */
    private static String lastWeekSql(String region) {
        return "select coalesce(sum(oi.amount), 0) as amount "
                + "from business.sales_order o "
                + "join business.order_item oi on oi.order_id = o.id "
                + "where o.region_code = '" + region + "' "
                + "and o.status <> 'CANCELLED' "
                + "and o.order_date >= date_trunc('week', current_date) - interval '7 days' "
                + "and o.order_date < date_trunc('week', current_date)";
    }

    private static ApplicationContextRunner runner(ScriptedChatModel model) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentAutoConfiguration.class,
                        ToolsAutoConfiguration.class,
                        SessionMemoryConfiguration.class,
                        JdbcSessionRepositoryAutoConfiguration.class,
                        SessionServiceAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.ai.session.repository.jdbc.initialize-schema=never")
                .withBean("chatModel", ChatModel.class, () -> model)
                .withBean(DataSource.class, () -> dataSource)
                .withBean(JdbcClient.class, () -> JdbcClient.create(dataSource))
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
    }

    @SuppressWarnings("unused")
    static boolean postgresImageIsAvailable() {
        return PostgresSupport.imageAvailable("SessionMemoryEndToEndTest");
    }
}
