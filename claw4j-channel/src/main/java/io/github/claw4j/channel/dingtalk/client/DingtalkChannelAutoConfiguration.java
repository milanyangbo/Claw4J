package io.github.claw4j.channel.dingtalk.client;

import io.github.claw4j.channel.dingtalk.DingtalkOutboundDelivery;
import io.github.claw4j.channel.dingtalk.DingtalkReplySender;
import io.github.claw4j.channel.http.HttpChannelProperties;
import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.domain.inbound.InboundStore;
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
 * 钉钉通道的装配。三条条件：
 *
 * <ol>
 *   <li>不是 servlet 应用 → 整份跳过。这条通道的入站是一个 HTTP 端点，没有 web 容器就没有它。</li>
 *   <li>三样配置不齐 → 端点、通道、发送器、投递都不注册。只跑 HTTP 的部署不该因为"没配钉钉"
 *       而起不来；认人解析器不依赖凭据，仍然注册，好在启动时就报出角色名写错。</li>
 *   <li>配齐了但凭据错、后台没填地址 → 不在这里判断，交给第一次回调与第一次发送：
 *       验签不过只记 WARN 回空串（重试不会变好），发送失败抛出去记 ERROR。
 *       与飞书"连不上就启动失败"不同，这边没有由它自己发起的连接可供探活。</li>
 * </ol>
 *
 * <p>{@link InboundDispatcher} 没有注册成 bean，在 {@code dingtalkCallbackController} 里就地建一个：
 * 容器里已经有三个（飞书、企微、Slack），再注册第四个，按类型注入的地方就没法用了。
 * 它也只服务这一个控制器。
 *
 * <p>这里同时绑定 {@link HttpChannelProperties}：各通道共用的授权表。
 *
 * <p>凭据是环境变量占位，不是 P08 的 {@code secret:} 句柄，与另外三个通道一致。
 * 这是记下来的偏差（见 {@link DingtalkProperties} 的类注释）：解析句柄的
 * {@code SpringSecretResolver} 在 capability，channel 不依赖它。
 */
@AutoConfiguration(afterName = "io.github.claw4j.agent.AgentAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "claw4j.channel.dingtalk", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({DingtalkProperties.class, HttpChannelProperties.class})
public class DingtalkChannelAutoConfiguration {

    /** 钉钉回推专用的线程池名字：与 HTTP、飞书、企微、Slack 分开，日志里一眼看出是哪条通道在跑。 */
    public static final String DINGTALK_EXECUTOR = "claw4jDingtalkExecutor";

    /**
     * 认人：本通道的登记表加各通道共用的授权表。角色名对不上时它自己让启动失败，
     * 所以这里不加兜底——兜底等于把"配置写错了"变成"那群人没有权限"。
     *
     * <p>放在 {@code CallbackConfiguration} 之外：它不需要凭据，
     * 而角色名写错在凭据配齐之前就该报出来。
     */
    @Bean
    @ConditionalOnMissingBean
    public DingtalkPrincipalResolver dingtalkPrincipalResolver(DingtalkProperties properties,
                                                               HttpChannelProperties authz) {
        return new DingtalkPrincipalResolver(properties, authz);
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(DingtalkConfiguredCondition.class)
    @ConditionalOnBean(AgentPort.class)
    static class CallbackConfiguration {

        /**
         * 跑 Agent 的池。有界，理由与另外两个一样：一条 Text-to-SQL 十几秒，
         * 无限池在人多的时候会把进程拖垮；满了就地拒绝，编排层回一句"稍后再试"。
         */
        @Bean(DINGTALK_EXECUTOR)
        @ConditionalOnMissingBean(name = DINGTALK_EXECUTOR)
        public Executor claw4jDingtalkExecutor() {
            var executor = new ThreadPoolTaskExecutor();
            executor.setThreadNamePrefix("claw4j-dingtalk-");
            executor.setCorePoolSize(4);
            executor.setMaxPoolSize(16);
            executor.setQueueCapacity(100);
            executor.initialize();
            return executor;
        }

        /**
         * 验签。构造时就校验 client-secret 非空：空密钥能算出签名，
         * 于是它会让任何自造请求都"验得过"——那不是配置不全，那是把门开着。
         */
        @Bean
        @ConditionalOnMissingBean
        public DingtalkSignature dingtalkSignature(DingtalkProperties properties) {
            return new DingtalkSignature(properties.getClientSecret());
        }

        /** 回推出口：换 token 加发消息，限流记 WARN 再抛（见 {@link DingtalkHttpReplySender}）。 */
        @Bean
        @ConditionalOnMissingBean
        public DingtalkReplySender dingtalkReplySender(DingtalkProperties properties) {
            return new DingtalkHttpReplySender(properties);
        }

        /**
         * 主动投递：结果回到钉钉那个成员（A05）。
         *
         * <p>条件写的是名字而不是类型：{@code @ConditionalOnMissingBean(OutboundDelivery.class)}
         * 会让后来的那个通道的投递永远注册不上——四个通道的 bean 类型相同。
         */
        @Bean
        @ConditionalOnMissingBean(name = "dingtalkOutboundDelivery")
        public OutboundDelivery dingtalkOutboundDelivery(DingtalkReplySender replySender) {
            return new DingtalkOutboundDelivery(replySender);
        }

        /**
         * 回调端点。它同时是"入站编排"的持有者：调度器在这里就地建，并把回执文案给它
         * （见 {@link DingtalkChannel#RECEIPT_REPLY} 与 {@code InboundDispatcher} 的构造器）。
         *
         * <p>通道标识传的是 {@code dingtalk}：从这里问出去的每一件事都会把"这个成员的会话"
         * 登记成收件地址。少传它的后果不是报错，是"在钉钉开的头，别处做完之后这里什么也收不到"。
         */
        @Bean
        @ConditionalOnMissingBean
        public DingtalkCallbackController dingtalkCallbackController(
                DingtalkSignature signature,
                AgentPort agentPort,
                @Qualifier(DINGTALK_EXECUTOR) Executor dingtalkExecutor,
                DingtalkPrincipalResolver principalResolver,
                DingtalkReplySender replySender,
                ObjectProvider<InboundStore> store) {
            InboundDispatcher dispatcher = new InboundDispatcher(agentPort, dingtalkExecutor, principalResolver,
                    replySender, "钉钉", DingtalkOutboundDelivery.CHANNEL, DingtalkChannel.RECEIPT_REPLY,
                    store.getIfAvailable());
            // 启动恢复 + 定时扫描。必须在端点开始接请求之前（见 Slack 那边同一句）。
            dispatcher.start();
            return new DingtalkCallbackController(signature, dispatcher, replySender);
        }

        /** 通道声明，启动时只记日志（见 {@link DingtalkChannel}）。 */
        @Bean(initMethod = "start", destroyMethod = "stop")
        @ConditionalOnMissingBean
        public DingtalkChannel dingtalkChannel(DingtalkProperties properties) {
            return new DingtalkChannel(properties);
        }
    }
}
