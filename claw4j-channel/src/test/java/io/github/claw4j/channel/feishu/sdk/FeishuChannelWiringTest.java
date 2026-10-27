package io.github.claw4j.channel.feishu.sdk;

import io.github.claw4j.channel.feishu.FeishuReplySender;
import io.github.claw4j.channel.feishu.InboundDispatcher;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
class FeishuChannelWiringTest {

    private static final String APP_ID = "claw4j.channel.feishu.app-id";

    private static final String APP_SECRET = "claw4j.channel.feishu.app-secret";

    private static final String DUPLICATE_MESSAGE =
            "claw4j.channel.feishu.roles 里同一个人登记了多个角色：ou_zhang。一个人只能有一个身份，请删掉多余的那几处。";

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
                        "claw4j.channel.feishu.roles.read-only.users[0]=ou_zhang",
                        "claw4j.channel.feishu.roles.guest.tables[0]=region",
                        "claw4j.channel.feishu.roles.guest.actions[0]=read",
                        "claw4j.channel.feishu.roles.guest.users[0]=ou_li")
                .run(context -> {
                    FeishuChannelProperties properties = context.getBean(FeishuChannelProperties.class);

                    assertThat(properties.resolve("ou_zhang")).get()
                            .extracting(Principal::role).isEqualTo("read-only");
                    assertThat(properties.resolve("ou_li")).get()
                            .extracting(Principal::role).isEqualTo("guest");
                    assertThat(properties.resolve("ou_stranger")).isEmpty();
                });
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

    /** 通道只需要一个 AgentPort 在场；这一份用例不验它做什么。 */
    private static final class NoopAgent implements AgentPort {

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
