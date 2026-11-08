package io.github.claw4j.channel.slack.client;

import io.github.claw4j.channel.http.HttpChannelProperties;
import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.domain.inbound.InboundStore;
import io.github.claw4j.channel.slack.SlackOutboundDelivery;
import io.github.claw4j.channel.slack.SlackReplySender;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.OutboundDelivery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Slack 通道的装配。三条条件，与前三个通道一一对应：
 *
 * <ol>
 *   <li><b>不是 servlet 应用</b> → 整份跳过。这条通道的入站是一个 HTTP 端点，
 *       没有 web 容器就没有它。</li>
 *   <li><b>两样凭据不齐</b> → 一个 bean 都不注册。只跑 HTTP 的部署不该因为"没配 Slack"而起不来。</li>
 *   <li><b>配齐了但凭据错／后台没填地址</b> → 不在这里判断，交给第一次回调与第一次发送：
 *       验签不过只记 WARN 回空串（重试不会变好），发送失败抛出去记 ERROR。</li>
 * </ol>
 *
 * <p>{@link InboundDispatcher} <b>没有注册成 bean</b>，就在下面那个
 * {@code slackCallbackController} 里就地建一个。理由与前三个通道一样，而且到这一版
 * 已经更严重：容器里已经有三个（飞书、企微、钉钉），再注册第四个的话，
 * 按类型注入的地方彻底没法用。它本来也只服务这一个控制器。
 *
 * <p>另外这里同时绑定 {@link HttpChannelProperties}：它是<b>四个通道共用的那张授权表</b>。
 *
 * <p><b>凭据是环境变量占位，不是 P08 的 {@code secret:} 句柄</b>，与另外三个通道一致。
 * 这是被明确记下来的偏差（见 {@link SlackProperties} 的类注释与 P12 的 Comments）：
 * 解析句柄的 {@code SpringSecretResolver} 在 capability，channel 不依赖它。
 */
@AutoConfiguration(afterName = "io.github.claw4j.agent.AgentAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "claw4j.channel.slack", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({SlackProperties.class, HttpChannelProperties.class})
public class SlackChannelAutoConfiguration {

    /** Slack 回推专用的线程池名字：与 HTTP、飞书、企微、钉钉分开，日志里一眼看出是哪条通道在跑。 */
    public static final String SLACK_EXECUTOR = "claw4jSlackExecutor";

    /**
     * 认人：本通道的登记表 + 四个通道共用的授权表。角色名对不上时它自己让启动失败，
     * 所以这里不加兜底——兜底等于把"配置写错了"变成"那群人没有权限"。
     *
     * <p>放在 {@code CallbackConfiguration} 之外：它不需要凭据，
     * 而角色名写错在凭据配齐之前就该报出来。
     */
    @Bean
    @ConditionalOnMissingBean
    public SlackPrincipalResolver slackPrincipalResolver(SlackProperties properties,
                                                         HttpChannelProperties authz) {
        return new SlackPrincipalResolver(properties, authz);
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(SlackConfiguredCondition.class)
    @ConditionalOnBean(AgentPort.class)
    static class CallbackConfiguration {

        /**
         * 跑 Agent 的池。有界，理由与另外三个一样：一条 Text-to-SQL 十几秒，
         * 无限池在人多的时候会把进程拖垮；满了就地拒绝，编排层回一句"稍后再试"。
         */
        @Bean(SLACK_EXECUTOR)
        @ConditionalOnMissingBean(name = SLACK_EXECUTOR)
        public Executor claw4jSlackExecutor() {
            var executor = new ThreadPoolTaskExecutor();
            executor.setThreadNamePrefix("claw4j-slack-");
            executor.setCorePoolSize(4);
            executor.setMaxPoolSize(16);
            executor.setQueueCapacity(100);
            executor.initialize();
            return executor;
        }

        /**
         * 验签。构造时就校验 signing-secret 非空：空密钥能算出签名，
         * 于是它会让任何自造请求都"验得过"——那不是配置不全，那是把门开着。
         */
        @Bean
        @ConditionalOnMissingBean
        public SlackSignature slackSignature(SlackProperties properties) {
            return new SlackSignature(properties.getSigningSecret());
        }

        @Bean
        @ConditionalOnMissingBean
        public SlackReplySender slackReplySender(SlackProperties properties) {
            return new SlackHttpReplySender(properties);
        }

        /**
         * 主动投递：结果回到那条会话（A05）。
         *
         * <p>条件写的是<b>名字</b>而不是类型：{@code @ConditionalOnMissingBean(OutboundDelivery.class)}
         * 会让后来的那个通道的投递永远注册不上——四个通道的 bean 类型相同。
         * 这是 P06 修过的真 bug，另三份也有同一句话。
         */
        @Bean
        @ConditionalOnMissingBean(name = "slackOutboundDelivery")
        public OutboundDelivery slackOutboundDelivery(SlackReplySender replySender) {
            return new SlackOutboundDelivery(replySender);
        }

        /**
         * 事件端点。它同时是"入站编排"的持有者：调度器在这里就地建，并把回执文案给它
         * （见 {@link SlackChannel#RECEIPT_REPLY} 与 {@code InboundDispatcher} 的构造器）。
         *
         * <p>通道标识传的是 {@code slack}：从这里问出去的每一件事都会把"这条会话"登记成收件地址。
         * 少传它的后果不是报错，是"在 Slack 开的头，别处做完之后这里什么也收不到"。
         */
        @Bean
        @ConditionalOnMissingBean
        public SlackCallbackController slackCallbackController(
                SlackSignature signature,
                AgentPort agentPort,
                @Qualifier(SLACK_EXECUTOR) Executor slackExecutor,
                SlackPrincipalResolver principalResolver,
                SlackReplySender replySender,
                ObjectProvider<InboundStore> store) {
            InboundDispatcher dispatcher = new InboundDispatcher(agentPort, slackExecutor, principalResolver,
                    replySender, "Slack", SlackOutboundDelivery.CHANNEL, SlackChannel.RECEIPT_REPLY,
                    store.getIfAvailable());
            // 启动恢复（把上一个进程留下的 claimed 收回）+ 定时扫描。**必须在端点开始接请求之前**：
            // 晚一步的话，重启之后那条没跑完的消息要等到第一次定时扫描才有人接着跑。
            // 扫描线程是守护线程，所以这里没有配对的 stop()——JVM 该退还是退（票 04）。
            dispatcher.start();
            return new SlackCallbackController(signature, dispatcher, replySender);
        }

        @Bean(initMethod = "start", destroyMethod = "stop")
        @ConditionalOnMissingBean
        public SlackChannel slackChannel(SlackProperties properties) {
            return new SlackChannel(properties);
        }
    }
}
