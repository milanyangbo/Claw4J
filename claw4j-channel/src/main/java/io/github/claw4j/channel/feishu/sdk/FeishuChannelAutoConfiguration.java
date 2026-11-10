package io.github.claw4j.channel.feishu.sdk;

import io.github.claw4j.channel.feishu.FeishuOutboundDelivery;
import io.github.claw4j.channel.feishu.FeishuReplySender;
import io.github.claw4j.channel.http.HttpChannelProperties;
import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.domain.inbound.InboundStore;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.ApprovalCards;
import io.github.claw4j.domain.port.ApprovalDecider;
import io.github.claw4j.domain.port.OutboundDelivery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 飞书通道的装配。三条条件，各自对应一种真实的部署形态：
 *
 * <ol>
 *   <li>SDK 不在 classpath（依赖被误删，或换了不含它的构建产物）：整个配置类跳过。
 *       条件写类名字符串而不是类引用——写成 {@code @ConditionalOnClass(Client.class)} 通常也能工作，
 *       但哪天有人在方法签名里引了 SDK 类型，缺包时会变成 {@code NoClassDefFoundError}，
 *       而不是安静地跳过。</li>
 *   <li>凭据不全：一个 bean 都不注册。只跑 HTTP 的部署不该因为没配飞书而起不来，
 *       也不该在容器里留一个拿空凭据建出来的客户端。</li>
 *   <li>凭据齐了但连不上：不在这里判断，交给 {@link FeishuLongConnectionChannel#start()}，
 *       它把失败分成两类（票 02）。凭据、应用状态这类换个时间点还是错的仍然抛，启动失败；
 *       网络、握手超时、对端不可用这类等一会儿会好的不抛，通道照常装配，后台退避重连，
 *       状态是 {@code DISCONNECTED}。一次对端抖动不该让整个应用起不来。</li>
 * </ol>
 *
 * <p>这里同时绑定 {@link HttpChannelProperties}：它是两个通道共用的那张授权表。
 * 飞书侧只登记「谁是谁」，表与动作按角色名去那张表里取（{@link FeishuPrincipalResolver}）。
 */
@AutoConfiguration(afterName = "io.github.claw4j.agent.AgentAutoConfiguration")
@ConditionalOnClass(name = "com.lark.oapi.ws.Client")
@ConditionalOnProperty(prefix = "claw4j.channel.feishu", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({FeishuChannelProperties.class, HttpChannelProperties.class})
public class FeishuChannelAutoConfiguration {

    /** 飞书回推专用的线程池名字：与 HTTP 的流式池分开，日志里一眼能看出是哪条通道在跑。 */
    public static final String FEISHU_EXECUTOR = "claw4jFeishuExecutor";

    /**
     * 认人：本通道的登记表加两个通道共用的授权表。
     *
     * <p>角色名对不上时它自己让启动失败，这里不加兜底——兜底等于把配置写错变成那群人没有权限。
     * 放在 {@code ChannelConfiguration} 之外，因为它不需要凭据；放进去意味着没配凭据时
     * 写错的角色名一路无声，等凭据配好那天才发现。
     */
    @Bean
    @ConditionalOnMissingBean
    public FeishuPrincipalResolver feishuPrincipalResolver(FeishuChannelProperties properties,
                                                           HttpChannelProperties authz) {
        return new FeishuPrincipalResolver(properties, authz);
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(FeishuConfiguredCondition.class)
    @ConditionalOnBean(AgentPort.class)
    static class ChannelConfiguration {

        /**
         * 跑 Agent 的池，有界：一条 Text-to-SQL 十几秒，无限池在群里人多时会把进程拖垮。
         * 满了就地拒绝，调度层回一句稍后再试——比无声排队好，用户至少知道发生了什么。
         */
        @Bean(FEISHU_EXECUTOR)
        @ConditionalOnMissingBean(name = FEISHU_EXECUTOR)
        public Executor claw4jFeishuExecutor() {
            var executor = new ThreadPoolTaskExecutor();
            executor.setThreadNamePrefix("claw4j-feishu-");
            executor.setCorePoolSize(4);
            executor.setMaxPoolSize(16);
            executor.setQueueCapacity(100);
            executor.initialize();
            return executor;
        }

        @Bean
        @ConditionalOnMissingBean
        public FeishuMessageTranslator feishuMessageTranslator() {
            return new FeishuMessageTranslator();
        }

        @Bean
        @ConditionalOnMissingBean
        public FeishuReplySender feishuReplySender(FeishuChannelProperties properties) {
            return new FeishuOpenApiReplySender(properties.getAppId(), properties.getAppSecret());
        }

        /**
         * 入站编排（票 04）：先落库、再 ACK。
         *
         * <p>{@code store} 可以为空：没有 DataSource 的部署用内存实现，行为与不落库时一致
         * （重启后没跑完的那条不会有人接着跑），并自己 WARN 一句。有库时它拿
         * {@code claw4j_channel.inbound_event}，重启不丢、同对话串行。
         *
         * <p>生命周期交给容器：{@code start()} 做启动恢复（收回上一个进程留下的 {@code claimed}）
         * 并起定时扫描，{@code stop()} 停掉它。不交给容器，这两步都得靠人手调，
         * 漏调的表现是重启之后没人接着跑，正是这一票要修的那件事。
         */
        @Bean(initMethod = "start", destroyMethod = "stop")
        @ConditionalOnMissingBean(name = "feishuInboundDispatcher")
        public InboundDispatcher feishuInboundDispatcher(AgentPort agentPort,
                                                         @Qualifier(FEISHU_EXECUTOR) Executor feishuExecutor,
                                                         FeishuReplySender replySender,
                                                         FeishuPrincipalResolver principalResolver,
                                                         ObjectProvider<InboundStore> store) {
            // 通道标识进去之后，从这里问出去的每一件事都会把"飞书这个会话"登记成收件地址。
            // 少传它的后果不是报错，是"在飞书开的头，别处做完之后飞书这头什么也收不到"。
            return new InboundDispatcher(agentPort, feishuExecutor, principalResolver, replySender,
                    "飞书", FeishuOutboundDelivery.CHANNEL, null, store.getIfAvailable());
        }

        /**
         * 主动投递：结果回到飞书那个会话（A05）。
         *
         * <p>只在凭据配齐时才有这个 bean（外层那条 {@code @Conditional}）。没有凭据就拿不到发送器，
         * 而「有一个什么都不做的 send」比「这个通道不支持推」难查得多。没有它时结果仍留在对话与审计里，
         * 只是没有人被主动通知。
         *
         * <p>条件写名字而不是类型：{@code @ConditionalOnMissingBean(OutboundDelivery.class)}
         * 会让第二个能推的通道（企微）永远注册不上——两者 bean 类型相同，谁先装配谁赢，
         * 而先后取决于自动配置排序。表现是库里登记了一行地址、却没有实现能送。
         */
        @Bean
        @ConditionalOnMissingBean(name = "feishuOutboundDelivery")
        public OutboundDelivery feishuOutboundDelivery(FeishuReplySender replySender) {
            return new FeishuOutboundDelivery(replySender);
        }

        /**
         * 审批卡片（P03）：这个通道把一条待批请求画出来并推回会话的能力。
         *
         * <p>注册成 {@link ApprovalCards} 而不是只在通道内部用：Agent 侧的审批门按 {@code channel()}
         * 找实现，它与飞书没有编译期关系（ADR-0010）。
         *
         * <p>条件写名字而不是类型，理由同上：按类型判会让第二个能画卡片的通道永远注册不上。
         */
        @Bean
        @ConditionalOnMissingBean(name = "feishuApprovalCards")
        public ApprovalCards feishuApprovalCards(FeishuChannelProperties properties) {
            return new FeishuApprovalCards(properties.getAppId(), properties.getAppSecret());
        }

        /**
         * 建连的工厂。单拎成 bean 是为了让「连不上」可测：默认实现既没有凭据也没有网络，
         * 重连与失败分类只能靠人工拔网线来验。
         */
        @Bean
        @ConditionalOnMissingBean
        public FeishuConnectionFactory feishuConnectionFactory(FeishuChannelProperties properties) {
            return new LarkFeishuConnectionFactory(properties);
        }

        /**
         * 通道本体。卡片回调在装配时定下来：没有 {@link ApprovalDecider}（没开 run 存储）
         * 就不注册，那种部署里也没有卡片可点，注册一个「点了说没装审批」的回调只是噪音。
         */
        @Bean(initMethod = "start", destroyMethod = "stop")
        @ConditionalOnMissingBean
        public FeishuLongConnectionChannel feishuChannel(FeishuChannelProperties properties,
                                                         InboundDispatcher dispatcher,
                                                         FeishuMessageTranslator translator,
                                                         FeishuReplySender replySender,
                                                         FeishuPrincipalResolver principalResolver,
                                                         FeishuConnectionFactory connectionFactory,
                                                         ObjectProvider<ApprovalDecider> deciders) {
            ApprovalDecider decider = deciders.getIfAvailable();
            FeishuApprovalActions approvals =
                    decider == null ? null : new FeishuApprovalActions(decider, principalResolver);
            return new FeishuLongConnectionChannel(properties, dispatcher, translator, replySender,
                    approvals, connectionFactory);
        }
    }
}
