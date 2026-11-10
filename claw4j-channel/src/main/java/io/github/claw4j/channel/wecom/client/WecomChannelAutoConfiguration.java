package io.github.claw4j.channel.wecom.client;

import io.github.claw4j.channel.http.HttpChannelProperties;
import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.domain.inbound.InboundStore;
import io.github.claw4j.channel.wecom.WecomOutboundDelivery;
import io.github.claw4j.channel.wecom.WecomReplySender;
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
 * 企业微信通道的装配。三条条件，与飞书那三条一一对应：
 *
 * <ol>
 *   <li>不是 servlet 应用：整份跳过。这条通道的入站是一个 HTTP 端点，没有 web 容器就没有它
 *       （飞书不需要这一条，它的入站是 SDK 自己连出去的）。</li>
 *   <li>五样配置不齐：一个 bean 都不注册。只跑 HTTP 的部署不该因为没配企微而起不来。</li>
 *   <li>配齐了但地址写错或凭据错：不在这里判断，交给第一次回调与第一次发送。回调签名对不上
 *       只记 WARN 回空串（重试不会变好），发送失败抛出去记 ERROR。与飞书「连不上就启动失败」
 *       不同，那边连接是它自己发起的，这边没有可供探活的东西。</li>
 * </ol>
 *
 * <p>{@link InboundDispatcher} 没有注册成 bean，而是在下面 {@code wecomCallbackController}
 * 里就地建一个。原因很实在：容器里已经有一个（飞书那份），两个同类型的 bean 会让按类型注入
 * 直接歧义，而飞书的通道 bean 正是按类型拿它的。它本来也只服务这一个控制器，
 * 露出去只是多一个能被别人注入的点。
 *
 * <p>这里同时绑定 {@link HttpChannelProperties}：它是两个通道共用的那张授权表。
 */
@AutoConfiguration(afterName = "io.github.claw4j.agent.AgentAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "claw4j.channel.wecom", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({WecomProperties.class, HttpChannelProperties.class})
public class WecomChannelAutoConfiguration {

    /** 企业微信回推专用的线程池名字：与 HTTP、飞书分开，日志里一眼看出是哪条通道在跑。 */
    public static final String WECOM_EXECUTOR = "claw4jWecomExecutor";

    /**
     * 认人：本通道的登记表加两个通道共用的授权表。
     *
     * <p>角色名对不上时它自己让启动失败，这里不加兜底——兜底等于把配置写错变成那群人没有权限。
     * 放在 {@code CallbackConfiguration} 之外，理由与飞书那份相同：它不需要凭据，
     * 而角色名写错在凭据配齐之前就该报出来。
     */
    @Bean
    @ConditionalOnMissingBean
    public WecomPrincipalResolver wecomPrincipalResolver(WecomProperties properties,
                                                         HttpChannelProperties authz) {
        return new WecomPrincipalResolver(properties, authz);
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(WecomConfiguredCondition.class)
    @ConditionalOnBean(AgentPort.class)
    static class CallbackConfiguration {

        /**
         * 跑 Agent 的池。有界，理由与飞书那个一样：一条 Text-to-SQL 十几秒，无限池在人多的
         * 时候会把进程拖垮；满了就地拒绝，编排层回一句稍后再试。
         */
        @Bean(WECOM_EXECUTOR)
        @ConditionalOnMissingBean(name = WECOM_EXECUTOR)
        public Executor claw4jWecomExecutor() {
            var executor = new ThreadPoolTaskExecutor();
            executor.setThreadNamePrefix("claw4j-wecom-");
            executor.setCorePoolSize(4);
            executor.setMaxPoolSize(16);
            executor.setQueueCapacity(100);
            executor.initialize();
            return executor;
        }

        /** 签名与解密。构造时校验 token、EncodingAESKey、corpId，写错了启动失败而不是等第一次回调。 */
        @Bean
        @ConditionalOnMissingBean
        public WecomCipher wecomCipher(WecomProperties properties) {
            return new WecomCipher(properties.getToken(), properties.getEncodingAesKey(), properties.getCorpId());
        }

        @Bean
        @ConditionalOnMissingBean
        public WecomReplySender wecomReplySender(WecomProperties properties) {
            return new WecomHttpReplySender(properties);
        }

        /**
         * 主动投递：结果回到企业微信那个成员（A05）。
         *
         * <p>条件写名字而不是类型：{@code @ConditionalOnMissingBean(OutboundDelivery.class)}
         * 会让后来的那个通道的投递永远注册不上，飞书与企微的 bean 类型相同。
         * 飞书那边（{@code feishuOutboundDelivery}）也是同一条，否则「在飞书问、企微收」
         * 这条路上只会有其中一个实现存在，而哪一个取决于装配顺序。
         */
        @Bean
        @ConditionalOnMissingBean(name = "wecomOutboundDelivery")
        public OutboundDelivery wecomOutboundDelivery(WecomReplySender replySender) {
            return new WecomOutboundDelivery(replySender);
        }

        /**
         * 回调端点。它同时是入站编排的持有者：调度器在这里就地建，回执文案也在这里传进去
         * （见 {@link WecomChannel#RECEIPT_REPLY} 与 {@link InboundDispatcher} 的构造器）。
         *
         * <p>通道标识传的是 {@code wecom}：从这里问出去的每一件事都会把这个成员的会话登记成
         * 收件地址。少传不报错，只是「在企微开的头，别处做完之后这里什么也收不到」。
         */
        @Bean
        @ConditionalOnMissingBean
        public WecomCallbackController wecomCallbackController(WecomCipher cipher,
                                                               AgentPort agentPort,
                                                               @Qualifier(WECOM_EXECUTOR) Executor wecomExecutor,
                                                               WecomPrincipalResolver principalResolver,
                                                               WecomReplySender replySender,
                                                               ObjectProvider<InboundStore> store) {
            InboundDispatcher dispatcher = new InboundDispatcher(agentPort, wecomExecutor, principalResolver,
                    replySender, "企业微信", WecomOutboundDelivery.CHANNEL, WecomChannel.RECEIPT_REPLY,
                    store.getIfAvailable());
            // 启动恢复 + 定时扫描。必须在端点开始接请求之前（见 Slack 那边同一句）。
            dispatcher.start();
            return new WecomCallbackController(cipher, dispatcher, replySender);
        }

        @Bean(initMethod = "start", destroyMethod = "stop")
        @ConditionalOnMissingBean
        public WecomChannel wecomChannel(WecomProperties properties) {
            return new WecomChannel(properties);
        }
    }
}
