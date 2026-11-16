package io.github.claw4j.agent.webtools;

import io.github.claw4j.capability.untrusted.UntrustedToolCallback;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.github.claw4j.domain.untrusted.ExternalContent;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 抓网页：{@code WebFetch}。抓回来让模型按 prompt 提炼，所以它比别的工具多一个依赖——模型。
 *
 * <p>放在 agent 模块而不是能力层，理由只有一个：它要 {@code ChatClient}，而那是这里造的。
 * 工具本身的行为一行没改，用的是库的 {@link SmartWebFetchTool}。
 *
 * <p><b>用来提炼的那个 {@code ChatClient} 不给容器</b>（下面现场建、随手用）：注册成 bean 之后
 * 容器里就有两个 {@code ChatClient}，{@code AgentAutoConfiguration} 的
 * {@code @ConditionalOnMissingBean(ChatClient.class)} 会让位给先到的那个，主聊天客户端直接消失，
 * 表现是整条 Advisor 链与工具循环全没了、且启动不报错。
 *
 * <p>它也不带任何 Advisor 与工具：提炼是一句话进一句话出，套上缓存会把摘要写进语义缓存，
 * 套上工具循环会让"抓一个网页"顺带烧掉几次工具预算。
 */
@AutoConfiguration
public class WebFetchAutoConfiguration {

    /** 工具名是库的 {@code @Tool(name = "WebFetch")} 定的。 */
    public static final String TOOL_NAME = "WebFetch";

    /** 出网读一个 URL。与 {@code web_search} 分开：抓一个确定的地址和搜一次，风险不一样。 */
    public static final String ACTION = "web_fetch";

    /**
     * 抓网页工具。库的默认值都没动：内容上限 100KB、域名安全检查开、缓存 100 条、失败重试 2 次。
     *
     * @param chatModel 模型客户端，用于提炼抓回来的内容
     * @return 抓网页工具
     */
    @Bean
    @ConditionalOnMissingBean
    public SmartWebFetchTool smartWebFetchTool(ChatModel chatModel) {
        ChatClient summarizer = ChatClient.builder(chatModel).build();
        return SmartWebFetchTool.builder(summarizer)
                // 关掉库自带的"域名安全检查"：它只是问 https://claude.ai/api/web/domain_info 一句黑名单，
                // 不判 IP 段，而且在国内恒返回 403 region_unavailable——开着就是"所有抓取全被拦"。
                // 出网边界改由 WebFetchGuard 按解析后的 IP 判（票 03），比那份黑名单更靠得住。
                .domainSafetyCheck(false)
                .build();
    }

    /**
     * 把工具交出去。
     *
     * @param tool 抓网页工具
     * @return 只含 {@code WebFetch} 的 provider
     */
    @Bean
    public ToolCallbackProvider webFetchTools(SmartWebFetchTool tool) {
        ToolCallback[] exported = MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks();
        // 两层，顺序不能换：
        //   外层 guard 先判 URL（拒绝内网/非法协议），通过才执行；
        //   内层 wrapping 把抓回来的内容包上边界与来源行。
        // 反过来的话，先抓再判等于洞已经开了。
        return ToolCallbackProvider.from(Arrays.stream(exported)
                .map(callback -> WebFetchGuard.guarding(
                        UntrustedToolCallback.wrapping(callback, ExternalContent.Source.WEB_FETCH, true)))
                .toList());
    }

    /**
     * 权限登记：动作 {@code web_fetch}，业务表一张都没有。
     *
     * @return 这一层的权限表
     */
    @Bean
    public ToolPermissionRegistry webFetchPermissionRegistry() {
        return ToolPermissionRegistry.of(Map.of(TOOL_NAME, ToolPermission.of(ACTION)));
    }

    /**
     * 档位：{@link ToolTier#CODING}。
     *
     * <p>与文件工具、{@code Bash} 同档：出网抓一个 URL 不是"消息与业务只读"。放在 {@code MESSAGING}
     * 会让"只做消息"的部署多出一条出网通道，而那类部署通常连出网策略都没配。
     *
     * @return 这一层的档位表
     */
    @Bean
    public ToolTierRegistry webFetchTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(TOOL_NAME), ToolTier.CODING);
    }
}
