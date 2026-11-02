package io.github.claw4j.channel.feishu.sdk;

import io.github.claw4j.channel.feishu.FeishuOutboundDelivery;
import io.github.claw4j.channel.feishu.FeishuReplySender;
import io.github.claw4j.channel.http.HttpChannelProperties;
import io.github.claw4j.channel.kernel.InboundDispatcher;
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
 * 飞书通道的装配。三条条件，各自对应一个真实的部署形态：
 *
 * <ol>
 *   <li><b>SDK 不在 classpath</b>（依赖被误删，或换了不含它的构建产物）→ 整个配置类跳过。
 *       条件写的是类名字符串而不是类引用：写成 {@code @ConditionalOnClass(Client.class)}
 *       虽然通常也能工作，但一旦哪天有人在方法签名里引了 SDK 类型，缺包时就会变成
 *       {@code NoClassDefFoundError} 而不是"安静地跳过"。</li>
 *   <li><b>凭据不全</b> → 一个 bean 都不注册。只跑 HTTP 的部署不该因为"没配飞书"而起不来，
 *       也不该在容器里留一个拿空凭据建出来的客户端。</li>
 *   <li><b>凭据齐了但连不上</b> → 不在这里判断，交给 {@link FeishuLongConnectionChannel#start()}：
 *       它等握手超时之后抛，启动失败。这是 ADR-0009 的既有约定——半死的通道比起不来更难排查。</li>
 * </ol>
 *
 * <p>另外这里同时绑定 {@link HttpChannelProperties}：它是<b>两个通道共用的那张授权表</b>。
 * 飞书那边只登记"谁是谁"，表与动作按角色名去这张表里取（{@link FeishuPrincipalResolver}）。
 */
@AutoConfiguration(afterName = "io.github.claw4j.agent.AgentAutoConfiguration")
@ConditionalOnClass(name = "com.lark.oapi.ws.Client")
@ConditionalOnProperty(prefix = "claw4j.channel.feishu", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({FeishuChannelProperties.class, HttpChannelProperties.class})
public class FeishuChannelAutoConfiguration {

    /** 飞书回推专用的线程池名字：与 HTTP 的流式池分开，日志里一眼能看出是哪条通道在跑。 */
    public static final String FEISHU_EXECUTOR = "claw4jFeishuExecutor";

    /**
     * 认人：本通道的登记表 + 两个通道共用的授权表。角色名对不上时它自己让启动失败，
     * 所以这里不加兜底——兜底就等于把"配置写错了"变成"那群人没有权限"。
     *
     * <p><b>放在 {@code ChannelConfiguration} 之外</b>：它不需要凭据，而角色名写错在凭据配齐
     * 之前就该报出来。放在里面意味着"没配凭据时，写错的角色名一路无声"，等凭据配好那天才发现。
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
         * 跑 Agent 的池。有界：一条 Text-to-SQL 十几秒，无限池在群里人多的时候会把进程拖垮。
         * 满了就地拒绝，调度层会回一句"稍后再试"——比无声排队好，因为用户至少知道发生了什么。
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

        @Bean
        @ConditionalOnMissingBean
        public InboundDispatcher feishuInboundDispatcher(AgentPort agentPort,
                                                         @Qualifier(FEISHU_EXECUTOR) Executor feishuExecutor,
                                                         FeishuReplySender replySender,
                                                         FeishuPrincipalResolver principalResolver) {
            // 通道标识进去之后，从这里问出去的每一件事都会把"飞书这个会话"登记成收件地址。
            // 少传它的后果不是报错，是"在飞书开的头，别处做完之后飞书这头什么也收不到"。
            return new InboundDispatcher(agentPort, feishuExecutor, principalResolver, replySender,
                    "飞书", FeishuOutboundDelivery.CHANNEL);
        }

        /**
         * 主动投递：结果回到飞书那个会话（A05）。
         *
         * <p><b>只在凭据配齐时才有这个 bean</b>（本类外层那条 {@code @Conditional}）：
         * 没有凭据就拿不到发送器，而"有一个 send 什么都不做"比"这个通道不支持推"难查得多。
         * 没有它的时候，飞书侧的结果仍然查得到——那是 {@code GET /api/runs/{id}} 那一读的事。
         *
         * <p>条件写的是<b>名字</b>而不是类型：{@code @ConditionalOnMissingBean(OutboundDelivery.class)}
         * 会让第二个能推的通道（企微）永远注册不上——它们的 bean 类型相同，谁先装配谁赢。
         * 而"谁先"取决于自动配置的排序，于是"在飞书问、企微收"这条路上会少一个实现，
         * 表现是库里登记了一行地址、永远没有实现能送。
         */
        @Bean
        @ConditionalOnMissingBean(name = "feishuOutboundDelivery")
        public OutboundDelivery feishuOutboundDelivery(FeishuReplySender replySender) {
            return new FeishuOutboundDelivery(replySender);
        }

        /**
         * 审批卡片（P03）：这个通道"能把一条待批请求画出来并推回会话"的那个能力。
         *
         * <p>注册成 {@link ApprovalCards} 而不是只在通道内部用：Agent 侧的审批门按
         * {@code channel()} 找实现（它与飞书没有任何编译期关系，ADR-0010）。
         *
         * <p>条件写的是<b>名字</b>而不是类型：{@code @ConditionalOnMissingBean(ApprovalCards.class)}
         * 会让第二个能画卡片的通道（企微）永远注册不上——它们的 bean 类型相同。
         */
        @Bean
        @ConditionalOnMissingBean(name = "feishuApprovalCards")
        public ApprovalCards feishuApprovalCards(FeishuChannelProperties properties) {
            return new FeishuApprovalCards(properties.getAppId(), properties.getAppSecret());
        }

        /**
         * 通道本体。卡片按钮的处理在装配时定下来：没有 {@link ApprovalDecider}（没开 run 存储）
         * 就不注册卡片回调——那种部署里也没有卡片可点，注册一个"点了说没装审批"的回调只是噪音。
         */
        @Bean(initMethod = "start", destroyMethod = "stop")
        @ConditionalOnMissingBean
        public FeishuLongConnectionChannel feishuChannel(FeishuChannelProperties properties,
                                                         InboundDispatcher dispatcher,
                                                         FeishuMessageTranslator translator,
                                                         FeishuReplySender replySender,
                                                         FeishuPrincipalResolver principalResolver,
                                                         ObjectProvider<ApprovalDecider> deciders) {
            ApprovalDecider decider = deciders.getIfAvailable();
            FeishuApprovalActions approvals =
                    decider == null ? null : new FeishuApprovalActions(decider, principalResolver);
            return new FeishuLongConnectionChannel(properties, dispatcher, translator, replySender, approvals);
        }
    }
}
