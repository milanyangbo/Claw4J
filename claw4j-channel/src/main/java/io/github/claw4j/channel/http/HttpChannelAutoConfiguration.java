package io.github.claw4j.channel.http;

import io.github.claw4j.domain.port.AgentPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * HTTP 通道的装配。注册为自动配置，于是任何模块引入 {@code claw4j-channel} 就能拿到端点，
 * 不需要把 {@code io.github.claw4j.channel} 加进组件扫描路径。
 *
 * <p>它依赖一个 {@link AgentPort} bean：通道只认 domain 里的端口，具体是谁实现（M0 是
 * ChatClient、将来可能是别的东西）不归它管，接线由 app 模块完成（ADR-0010）。
 */
@AutoConfiguration(afterName = "io.github.claw4j.agent.AgentAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "claw4j.channel.http", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HttpChannelProperties.class)
public class HttpChannelAutoConfiguration {

    /**
     * 流式专用线程池的名字。单独命名是为了不与容器里其它 Executor 撞车，
     * 也方便在监控里一眼看出"有多少条流式请求在跑"。
     */
    public static final String STREAM_EXECUTOR = "claw4jStreamExecutor";

    @Bean
    @ConditionalOnMissingBean
    public HttpPrincipalResolver httpPrincipalResolver(HttpChannelProperties properties) {
        return new HttpPrincipalResolver(properties);
    }

    /**
     * 流式请求专用的线程池。有界：{@code stream()} 是阻塞调用，一条流式回答占一个线程，
     * 无限池会在流量上来时把整个进程拖垮。满了就拒绝，由控制器转成连接错误，
     * 好过让请求无声排队。
     */
    @Bean(STREAM_EXECUTOR)
    @ConditionalOnMissingBean(name = STREAM_EXECUTOR)
    public Executor claw4jStreamExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("claw4j-stream-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.initialize();
        return executor;
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    public HttpChatChannel httpChatChannel(AgentPort agentPort,
                                           @Qualifier(STREAM_EXECUTOR) Executor streamExecutor) {
        return new HttpChatChannel(agentPort, streamExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpChatController httpChatController(HttpChatChannel channel,
                                                 HttpPrincipalResolver principalResolver,
                                                 HttpChannelProperties properties) {
        return new HttpChatController(channel, principalResolver, properties);
    }
}
