package io.github.claw4j.channel.feishu.sdk;

import io.github.claw4j.channel.feishu.FeishuReplySender;
import io.github.claw4j.channel.feishu.InboundDispatcher;
import io.github.claw4j.domain.port.AgentPort;
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
 */
@AutoConfiguration(afterName = "io.github.claw4j.agent.AgentAutoConfiguration")
@ConditionalOnClass(name = "com.lark.oapi.ws.Client")
@ConditionalOnProperty(prefix = "claw4j.channel.feishu", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FeishuChannelProperties.class)
public class FeishuChannelAutoConfiguration {

    /** 飞书回推专用的线程池名字：与 HTTP 的流式池分开，日志里一眼能看出是哪条通道在跑。 */
    public static final String FEISHU_EXECUTOR = "claw4jFeishuExecutor";

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
                                                         FeishuChannelProperties properties) {
            return new InboundDispatcher(agentPort, feishuExecutor, properties::resolve, replySender);
        }

        @Bean(initMethod = "start", destroyMethod = "stop")
        @ConditionalOnMissingBean
        public FeishuLongConnectionChannel feishuChannel(FeishuChannelProperties properties,
                                                         InboundDispatcher dispatcher,
                                                         FeishuMessageTranslator translator,
                                                         FeishuReplySender replySender) {
            return new FeishuLongConnectionChannel(properties, dispatcher, translator, replySender);
        }
    }
}
