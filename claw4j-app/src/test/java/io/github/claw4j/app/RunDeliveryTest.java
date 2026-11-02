package io.github.claw4j.app;

import io.github.claw4j.agent.run.DeliveryTarget;
import io.github.claw4j.agent.run.JdbcRunStore;
import io.github.claw4j.agent.run.Run;
import io.github.claw4j.agent.run.RunDelivery;
import io.github.claw4j.agent.run.RunStatus;
import io.github.claw4j.agent.run.RunStore;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.principal.Principal;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结果投递与回收（A05，ADR-0018）真跑在 Postgres 上。
 *
 * <p><b>为什么单独一份</b>：这件事的正确性全在"地址是从库里读出来的"这一步上。
 * 内存版只能证明分支写对了，证明不了"换一个通道接着做时，新的地址真的一起进来了"——
 * 而那条路正是 A05 存在的理由。所以这里用真表，推送那一头用假的
 * （{@link OutboundDelivery} 的实现属于各通道，它自己那份测试管它）。
 *
 * <p><b>判据是"谁收到了什么"，不是"有没有报错"</b>：投递失败被设计成
 * 只记日志（结果仍在库里），所以"没抛异常"什么也证明不了。
 */
@Testcontainers
class RunDeliveryTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    private RunStore runs;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void freshTables() {
        jdbc.update("delete from claw4j_run.agent_run_delivery");
        jdbc.update("delete from claw4j_run.agent_step");
        jdbc.update("delete from claw4j_run.agent_run");
        runs = new JdbcRunStore(jdbc);
    }

    @Test
    @DisplayName("一件事登记两个地址：就地回答过的那个不重复推，另一个通道照样收到")
    void theOtherChannelGetsTheResult() {
        Recording deliveryFeishu = new Recording("feishu");
        Recording deliveryWeCom = new Recording("wecom");
        RunDelivery delivery = delivery(deliveryFeishu, deliveryWeCom);
        Run run = runs.start(owner("u-1"), "fp-1", "对齐三张表的口径").orElseThrow();

        // 在飞书开的头，在飞书里接着问了一句（就地回答了），企业微信那边也被登记过。
        delivery.register(run.id(), "feishu", "oc_zhang");
        delivery.register(run.id(), "wecom", "chat-9");
        runs.finish(run.id(), RunStatus.DONE, "三张表的口径对完了：成交额按支付时间算");
        delivery.announce(run.id(), RunStatus.DONE, "三张表的口径对完了：成交额按支付时间算",
                new DeliveryTarget("feishu", "oc_zhang"));

        assertThat(deliveryFeishu.sent).as("同一条内容出现两遍，人以为自己被重跑了").isEmpty();
        assertThat(deliveryWeCom.sent)
                .as("在别处接着做，原通道那头也要收到结果")
                .containsExactly("chat-9|三张表的口径对完了：成交额按支付时间算");
    }

    @Test
    @DisplayName("silent 一句都不推，但结果落库、按 id 查得到（不打扰不等于丢了）")
    void silentKeepsTheResultButSaysNothing() {
        Recording deliveryFeishu = new Recording("feishu");
        RunDelivery delivery = delivery(deliveryFeishu);
        Principal asker = Principal.readOnly("u-1");
        Run run = runs.start(owner("u-1"), "fp-1", "每周一跑一遍口径核对",
                NotifyPolicy.SILENT).orElseThrow();

        delivery.register(run.id(), "feishu", "oc_zhang");
        runs.finish(run.id(), RunStatus.DONE, "这周没有对不上的地方");
        delivery.announce(run.id(), RunStatus.DONE, "这周没有对不上的地方", null);

        assertThat(deliveryFeishu.sent).as("定时任务每跑一趟推一条，等于把自动化做成骚扰").isEmpty();
        assertThat(delivery.find(run.id(), asker)).get()
                .satisfies(view -> {
                    assertThat(view.result()).isEqualTo("这周没有对不上的地方");
                    assertThat(view.notifyPolicy()).isEqualTo(NotifyPolicy.SILENT);
                });
    }

    @Test
    @DisplayName("按 id 回收归 owner 判，不归通道判：本人查得到，换个人就是查不到")
    void theRunBelongsToAPersonNotAChannel() {
        RunDelivery delivery = delivery();
        Run run = runs.start(owner("u-1"), "fp-1", "上周华东成交额").orElseThrow();
        runs.finish(run.id(), RunStatus.DONE, "华东上周成交 128400");

        // 同一件事在飞书开的头，在 HTTP 上查得到——这正是"绑 principal 不绑通道"。
        assertThat(delivery.find(run.id(), Principal.readOnly("u-1")))
                .hasValueSatisfying(view -> assertThat(view.status()).isEqualTo("done"));
        assertThat(delivery.find(run.id(), Principal.readOnly("u-9")))
                .as("别人的事与没有这件事给同一个答案，不替对方确认 id 存在")
                .isEmpty();
    }

    @Test
    @DisplayName("通知策略三档写死在约束里：拼错的那一次连库都进不去")
    void unknownNotifyPolicyIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into claw4j_run.agent_run (id, owner, principal_digest, goal, status, notify_policy)"
                        + " values ('r-bad', 'u-1@fp', 'fp', 'goal', 'running', 'slience')"))
                .as("拼错的表现是「该说的一次没说」，那种错只在事后被发现")
                .hasMessageContaining("ck_agent_run_notify_policy");
    }

    @Test
    @DisplayName("同一个地址登记两次只留一行：连着问三轮，收到结果的还是一个会话")
    void theSameAddressIsRegisteredOnce() {
        RunDelivery delivery = delivery(new Recording("feishu"));
        Run run = runs.start(owner("u-1"), "fp-1", "goal").orElseThrow();

        delivery.register(run.id(), "feishu", "oc_zhang");
        delivery.register(run.id(), "feishu", "oc_zhang");

        assertThat(delivery.targetsOf(run.id()))
                .containsExactly(new DeliveryTarget("feishu", "oc_zhang"));
    }

    // ---------------------------------------------------------------- 装配

    private static String owner(String userId) {
        return SessionKeys.ownerOf(Principal.readOnly(userId));
    }

    private RunDelivery delivery(OutboundDelivery... channels) {
        // 没有 registry 时照常投递，只是没有指标——与 run 落库同一个降级口径。
        return new RunDelivery(runs, jdbc, List.of(channels), null);
    }

    /** 记下"谁收到了什么"，不抛：这一层要验的是投递的路由，不是平台的限流。 */
    private static final class Recording implements OutboundDelivery {

        private final String channel;

        private final List<String> sent = new ArrayList<>();

        Recording(String channel) {
            this.channel = channel;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public void send(String channelRef, String text) {
            sent.add(channelRef + "|" + text);
        }
    }
}
