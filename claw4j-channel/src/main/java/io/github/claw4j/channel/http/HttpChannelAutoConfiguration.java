package io.github.claw4j.channel.http;

import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.ApprovalDecider;
import io.github.claw4j.domain.port.RoleDirectory;
import io.github.claw4j.domain.port.RunQuery;
import org.springframework.beans.factory.ObjectProvider;
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
     * 角色表的另一个读者（P05）：给定"谁 + 什么角色"，回答此刻的权限。
     *
     * <p>定时任务到点那一刻没有人发消息，鉴权只能靠提前落库的身份；而权限不能跟着
     * 一起落库（那是角色快照），于是每次要跑之前来这里换一份当前的。
     *
     * <p>注册成 {@link RoleDirectory} 而不是具体类型：agent 只认这个端口，
     * 换一种配置源（库表、LDAP、外部授权服务）时这里换一个实现就够。
     */
    @Bean
    @ConditionalOnMissingBean(RoleDirectory.class)
    public RoleDirectory roleDirectory(HttpChannelProperties properties) {
        return new ConfiguredRoleDirectory(properties);
    }

    /**
     * 流式请求专用的线程池。有界：{@code stream()} 是阻塞调用，一条流式回答占一个线程，
     * 无限池会在流量上来时把整个进程拖垮。满了就地拒绝，控制器转成 503，
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

    /**
     * {@code GET /api/runs/{id}}（A05）：结果不跟"那一次请求"绑在一起之后，
     * 客户端要有一个地方按 id 把它取回去。
     *
     * <p>{@link RunQuery} 走 {@code ObjectProvider} 进去：没有装配 run 存储时
     * 端点仍在，只是全都 404——那是能力不在场，不是这个通道坏了。
     */
    @Bean
    @ConditionalOnMissingBean
    public RunQueryController runQueryController(ObjectProvider<RunQuery> runs,
                                                 HttpPrincipalResolver principalResolver) {
        return new RunQueryController(runs, principalResolver);
    }

    /**
     * {@code GET/POST /api/approvals/{id}}（P03）：待批请求的读法与批/拒的入口。
     *
     * <p>{@link ApprovalDecider} 同样走 {@code ObjectProvider}：没有装配审批的部署里
     * 端点仍在、只是全都 404——与 {@code /api/runs} 同一个口径，端点凭空消失比 404 难查。
     *
     * <p><b>它与飞书卡片调的是同一个 {@code decide}</b>：这是这张票里"HTTP 可以独立于飞书用"
     * 那句话的落点——两套入口、一套规矩。
     */
    @Bean
    @ConditionalOnMissingBean
    public ApprovalController approvalController(ObjectProvider<ApprovalDecider> deciders,
                                                 HttpPrincipalResolver principalResolver) {
        return new ApprovalController(deciders, principalResolver);
    }
}
