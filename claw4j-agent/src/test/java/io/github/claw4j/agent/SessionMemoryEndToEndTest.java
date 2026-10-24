package io.github.claw4j.agent;

import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.agent.session.SessionMemoryConfiguration;
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
import org.springaicommunity.session.jdbc.autoconfigure.JdbcSessionRepositoryAutoConfiguration;
import org.springaicommunity.session.autoconfigure.SessionServiceAutoConfiguration;
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
 * 06 号票的正面判据：<b>第二轮接着第一轮</b>——这回是真模型自己在接着问。
 *
 * <p>判据的形状没变：第二轮要答得出华南的数，看不见上一轮就只能反问或瞎猜。
 * 变的是**谁来答**：改真模型之前（ADR-0011）第二轮的回答是脚本按"提示词里有没有华东"
 * 现算的，于是"有记忆"与"没记忆"这两个分支其实由脚本背书。现在只有"华南那个数"
 * 是数据库给的，中间的推理全交给模型——它得自己看懂上一轮的口径并写出华南的 SQL。
 *
 * <p>会话存储是真的 JDBC（同一个 Postgres、另一个 schema），不是替身：
 * "多实例不失忆"是 ADR-0005 选 JDBC 的全部理由，用替身等于把要证明的东西替掉了。
 *
 * <p>涉及"隔离"与"落库内容"的那几条，判据查库而不是看回答——
 * 真模型的措辞不可控，但表里写没写、写进哪个会话，是确定的。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("postgresImageIsAvailable")
class SessionMemoryEndToEndTest {

    /** 02 号票的种子契约：上周华东 128400，上周华南 26000，上周华北 31500。 */
    private static final String EAST_AMOUNT = "128400";

    private static final String SOUTH_AMOUNT = "26000";

    private static final String NORTH_AMOUNT = "31500";

    /** 千分位写法，用于"不该出现"那类断言（两个区不能串）。 */
    private static final String SOUTH_GROUPED = "26,000";

    private static final String NORTH_GROUPED = "31,500";

    private static final String FIRST_QUESTION = "上周华东区成交额多少";

    private static final String FOLLOW_UP = "那华南呢";

    /** 换个用户问的问题：与 ethan 的第一轮不同，用来验"两个人不是同一个会话"。 */
    private static final String OTHER_USER_QUESTION = "上周华北区成交额多少";

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
        runner().run(context -> RealModelSupport.eventually(2, "追问拿到华南的数", () -> {
            AgentPort agent = context.getBean(AgentPort.class);

            String first = agent.reply(ask("ethan", "http:t1", FIRST_QUESTION)).text();
            RealModelSupport.assertContainsAmount(first, EAST_AMOUNT);

            OutboundMessage out = agent.reply(ask("ethan", "http:t1", FOLLOW_UP));

            // 判据：算的是华南，不是"又算了一遍华东"
            RealModelSupport.assertContainsAmount(out.text(), SOUTH_AMOUNT);
            // 历史确实落库了：上一轮的问句在会话里，而不是靠进程内存
            assertThat(storedContent()).anyMatch(content -> content != null && content.contains("华东"));
            return out.text();
        }));
    }

    @Test
    @DisplayName("流式路径同样接得上")
    void 流式路径的追问接得上() {
        runner().run(context -> RealModelSupport.eventually(2, "流式追问拿到华南的数", () -> {
            AgentPort agent = context.getBean(AgentPort.class);
            agent.reply(ask("ethan", "http:t1", FIRST_QUESTION));

            List<OutboundMessage> chunks = new ArrayList<>();
            agent.stream(ask("ethan", "http:t1", FOLLOW_UP), chunks::add);

            String streamed = chunks.stream().map(OutboundMessage::text).reduce("", String::concat);
            RealModelSupport.assertContainsAmount(streamed, SOUTH_AMOUNT);
            assertThat(chunks.getLast().finalChunk()).isTrue();
            return streamed;
        }));
    }

    /**
     * chatId 是通道侧的寻址键，不是会话主键：换一条通道进来，上下文还在。
     * 反过来（把 chatId 当主键）会让这一条拿不到华南的数。
     */
    @Test
    @DisplayName("换一条通道进来，上下文仍然连续")
    void 换通道不失忆() {
        runner().run(context -> RealModelSupport.eventually(2, "换通道后仍拿到华南的数", () -> {
            AgentPort agent = context.getBean(AgentPort.class);
            agent.reply(ask("ethan", "http:t1", FIRST_QUESTION));

            // 同一个人在飞书那边接着问：chatId 换了，会话没换
            OutboundMessage out = agent.reply(ask("ethan", "feishu:oc_abc", FOLLOW_UP));

            RealModelSupport.assertContainsAmount(out.text(), SOUTH_AMOUNT);
            return out.text();
        }));
    }

    /**
     * 会话按人隔离：换个用户就是另一个会话。
     *
     * <p>判据查库而不是看回答：真模型下"换个人问'那华南呢'会答出什么"是不可控的
     * （它可能照样猜出意图并去查华南），但**表里两个人的问句会不会落进同一个会话**
     * 是确定的。全局一份记忆那种实现会把这两句写进同一个 session_id，这里立刻红。
     */
    @Test
    @DisplayName("换一个人是干净的会话，不会串到别人的上下文")
    void 换个用户不串味() {
        runner().run(context -> RealModelSupport.eventually(2, "两个人的问句没落在同一个会话里", () -> {
            AgentPort agent = context.getBean(AgentPort.class);
            agent.reply(ask("ethan", "http:t1", FIRST_QUESTION));
            String other = agent.reply(ask("lisa", "http:t2", OTHER_USER_QUESTION)).text();
            RealModelSupport.assertContainsAmount(other, NORTH_AMOUNT);

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Integer shared = jdbc.queryForObject("""
                    select count(*) from (
                        select session_id
                        from claw4j_session.ai_session_event
                        group by session_id
                        having count(*) filter (where message_content like '%上周华东区成交额多少%') > 0
                           and count(*) filter (where message_content like '%上周华北区成交额多少%') > 0
                    ) leaked""", Integer.class);

            assertThat(shared).as("两个人的问句不该落在同一个会话里").isZero();
            return null;
        }));
    }

    /**
     * "多实例不失忆"是 ADR-0005 选 JDBC 的全部理由：两个上下文就是两个实例，
     * 它们不共享任何内存，只共享那张表。
     */
    @Test
    @DisplayName("两个应用实例之间记得住：记忆不在进程内存里")
    void 多实例之间记得住() {
        runner().run(first -> RealModelSupport.eventually(2, "第一个实例答出华东", () -> {
            String answer = first.getBean(AgentPort.class)
                    .reply(ask("ethan", "http:t1", FIRST_QUESTION)).text();
            RealModelSupport.assertContainsAmount(answer, EAST_AMOUNT);
            return answer;
        }));

        // 第二个实例：全新的容器、全新的连接池，只有一个 shared 的 Postgres
        runner().run(second -> RealModelSupport.eventually(2, "第二个实例接着答出华南", () -> {
            OutboundMessage out = second.getBean(AgentPort.class)
                    .reply(ask("ethan", "http:t1", FOLLOW_UP));
            RealModelSupport.assertContainsAmount(out.text(), SOUTH_AMOUNT);
            return out.text();
        }));
    }

    /**
     * 会话表与业务表同库不同 schema（ADR-0005）。
     * 只断言"表存在"是不够的：官方自动配置那份 repository 会把表建在 public，
     * 而 public 和业务库都在，所以这里连"business 里没有会话表"一起断言。
     */
    @Test
    @DisplayName("会话落在自己的 schema，没有混进业务 schema")
    void 会话表在自己的schema() {
        runner().run(context -> RealModelSupport.eventually(2, "会话写进了自己的 schema", () -> {
            context.getBean(AgentPort.class).reply(ask("ethan", "http:t1", FIRST_QUESTION));

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
            assertThat(storedContent()).isNotEmpty();
            return null;
        }));
    }

    /**
     * 会话里存的是脱敏之后的文本，不是原始号码。
     *
     * <p>出站脱敏了但会话表里躺着明文，是比出站更持久的一份泄露，而且没人会去看。
     * 这一条钉的是链的收尾顺序：脱敏先收尾，记忆后收尾（见
     * {@link SessionMemoryConfiguration#ADVISOR_ORDER}）。
     *
     * <p>硬判据是"库里任何一处都不出现明文"——这一条与模型说什么无关。
     * "出现了掩码"那条交给模型复述号码来触发，所以它带一次重试。
     */
    @Test
    @DisplayName("会话里存的是掩码后的文本，不是原始手机号")
    void 会话里存的是脱敏后的文本() {
        String withPhone = "我的手机号是 13800138000，请把它原样写进你的回答。";

        runner().run(context -> RealModelSupport.eventually(2, "出站与落库都是掩码", () -> {
            OutboundMessage out = context.getBean(AgentPort.class)
                    .reply(ask("ethan", "http:t1", withPhone));

            assertThat(out.text()).doesNotContain("13800138000");

            List<String> stored = storedContent();
            // 硬判据：明文一个字都不能进会话表
            assertThat(stored).noneMatch(content -> content != null && content.contains("13800138000"));
            // 掩码出现过，说明脱敏确实先于记忆收尾（而不是"两边都没存号码"蒙混过关）
            assertThat(stored).anyMatch(content -> content != null && content.contains("138****8000"));
            return out.text();
        }));
    }

    private static InboundMessage ask(String user, String chatId, String question) {
        return new InboundMessage(Principal.readOnly(user), chatId, question);
    }

    private static List<String> storedContent() {
        return new JdbcTemplate(dataSource).queryForList(
                "select message_content from claw4j_session.ai_session_event", String.class);
    }

    private static ApplicationContextRunner runner() {
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
                .withBean("chatModel", ChatModel.class, RealModelSupport::chatModel)
                .withBean(DataSource.class, () -> dataSource)
                .withBean(JdbcClient.class, () -> JdbcClient.create(dataSource))
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
    }

    @SuppressWarnings("unused")
    static boolean postgresImageIsAvailable() {
        return PostgresSupport.databaseAvailable("SessionMemoryEndToEndTest");
    }
}
