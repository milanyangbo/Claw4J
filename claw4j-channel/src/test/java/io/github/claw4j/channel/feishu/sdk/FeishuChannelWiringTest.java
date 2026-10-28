package io.github.claw4j.channel.feishu.sdk;

import io.github.claw4j.channel.feishu.FeishuReplySender;
import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 飞书通道的装配条件：什么情况下它在容器里，什么情况下它连一个 bean 都不留。
 *
 * <p>这一份测的不是"翻译 SDK 对象"（那要真凭据，靠起一次应用手验），而是**装配**：
 * 没配飞书的人、没打 SDK 的包、把通道关掉的人，都不该因为这个新通道而起不来。
 * 这三件事在真链路上完全看不出来——只跑 HTTP 的部署照样能启动，直到某次升级把它拖垮。
 *
 * <p>唯一的正例（凭据齐全 → 真连上）不写在这里：它会在跑测试时去连飞书，
 * 让回归依赖网络与一个真实应用的状态。
 */
@ExtendWith(OutputCaptureExtension.class)
class FeishuChannelWiringTest {

    private static final String APP_ID = "claw4j.channel.feishu.app-id";

    private static final String APP_SECRET = "claw4j.channel.feishu.app-secret";

    private static final String DUPLICATE_MESSAGE =
            "claw4j.channel.feishu.roles 里同一个人登记了多个角色：ou_zhang。一个人只能有一个身份，请删掉多余的那几处。";

    private static final String UNKNOWN_ROLE_MESSAGE =
            "claw4j.channel.feishu.roles 里的角色在 claw4j.channel.http.roles 里没有同名定义："
                    + "operator（登记了 1 人）。飞书只登记人，授权表共用 HTTP 那一份，角色名必须对得上。";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeishuChannelAutoConfiguration.class))
            .withBean(AgentPort.class, NoopAgent::new);

    @Test
    @DisplayName("凭据缺失：飞书通道一个 bean 都不留，上下文照常起来")
    void missingCredentialsLeaveTheChannelOut() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FeishuLongConnectionChannel.class);
            assertThat(context).doesNotHaveBean(InboundDispatcher.class);
            assertThat(context).doesNotHaveBean(FeishuReplySender.class);
        });
    }

    @Test
    @DisplayName("只配了 app-id 没给 secret：算没配，不能拿半套凭据去连")
    void halfCredentialsCountAsNotConfigured() {
        runner.withPropertyValues(APP_ID + "=cli_half")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FeishuLongConnectionChannel.class);
                });
    }

    @Test
    @DisplayName("SDK 不在 classpath：整个包退让，不出现 ClassNotFoundException")
    void withoutTheSdkTheChannelBacksOff() {
        runner.withPropertyValues(APP_ID + "=cli_x", APP_SECRET + "=secret")
                .withClassLoader(new FilteredClassLoader("com.lark"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FeishuLongConnectionChannel.class);
                });
    }

    @Test
    @DisplayName("enabled=false：凭据齐了也不装，方便只留 HTTP 的部署")
    void disabledByProperty() {
        runner.withPropertyValues(APP_ID + "=cli_x", APP_SECRET + "=secret",
                        "claw4j.channel.feishu.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FeishuLongConnectionChannel.class));
    }

    @Test
    @DisplayName("认人只看登记表：登记的人按角色拿到表与动作，没登记的人什么都没有（不回落默认角色）")
    void onlyRegisteredUsersResolveToAPrincipal() {
        runner.withPropertyValues(
                        // 授权只有一份：写在这张两个通道共用的表里
                        "claw4j.channel.http.roles.read-only.tables[0]=*",
                        "claw4j.channel.http.roles.read-only.actions[0]=read",
                        "claw4j.channel.http.roles.guest.tables[0]=region",
                        "claw4j.channel.http.roles.guest.actions[0]=read",
                        // 飞书这边只回答"这个人是谁"
                        "claw4j.channel.feishu.roles.read-only.users[0]=ou_zhang",
                        "claw4j.channel.feishu.roles.guest.users[0]=ou_li")
                .run(context -> {
                    FeishuPrincipalResolver resolver = context.getBean(FeishuPrincipalResolver.class);

                    assertThat(resolver.apply("ou_zhang")).get()
                            .satisfies(principal -> {
                                assertThat(principal.role()).isEqualTo("read-only");
                                assertThat(principal.accessibleTables())
                                        .as("表与动作不是飞书那边配的，是从共用的授权表里按角色名取的")
                                        .containsExactly("*");
                            });
                    assertThat(resolver.apply("ou_li")).get()
                            .satisfies(principal -> {
                                assertThat(principal.role()).isEqualTo("guest");
                                assertThat(principal.accessibleTables()).containsExactly("region");
                            });
                    assertThat(resolver.apply("ou_stranger")).isEmpty();
                });
    }

    @Test
    @DisplayName("飞书登记的角色在共用授权表里没有同名定义：启动失败并点名，别让那群人天天收到「没有权限」")
    void unknownRoleFailsAtStartup() {
        runner.withPropertyValues(
                        "claw4j.channel.http.roles.read-only.tables[0]=*",
                        "claw4j.channel.http.roles.read-only.actions[0]=read",
                        "claw4j.channel.feishu.roles.operator.users[0]=ou_wang")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage(UNKNOWN_ROLE_MESSAGE));
    }

    @Test
    @DisplayName("同一个人登记了两个角色：启动失败并点名是谁，而不是按书写顺序挑一个")
    void duplicateRegistrationFailsAtStartup() {
        runner.withPropertyValues(
                        "claw4j.channel.feishu.roles.read-only.users[0]=ou_zhang",
                        "claw4j.channel.feishu.roles.guest.users[0]=ou_zhang")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage(DUPLICATE_MESSAGE));
    }

    @Test
    @DisplayName("凭据齐了、roles 里一个人都没登记：启动日志里就要说，别让“全员没有权限”看起来像服务没起来")
    void nobodyRegisteredIsCalledOutAtStartup(CapturedOutput output) {
        FeishuChannelProperties properties = new FeishuChannelProperties();
        properties.setAppId("cli_x");
        properties.setAppSecret("secret");

        properties.validate();

        assertThat(output).contains("一个人都没登记");
    }

    @Test
    @DisplayName("没配凭据时不吵：只跑 HTTP 的部署不该看到飞书通道的告警")
    void unconfiguredChannelSaysNothing(CapturedOutput output) {
        new FeishuChannelProperties().validate();

        assertThat(output).doesNotContain("一个人都没登记");
    }

    /** 通道只需要一个 AgentPort 在场；这一份用例不验它做什么。 */    private static final class NoopAgent implements AgentPort {

        @Override
        public OutboundMessage reply(InboundMessage message) {
            return OutboundMessage.of(message.chatId(), "答复");
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            throw new UnsupportedOperationException("飞书走一次性回答");
        }
    }
}
