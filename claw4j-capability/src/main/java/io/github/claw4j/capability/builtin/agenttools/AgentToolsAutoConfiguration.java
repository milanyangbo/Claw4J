package io.github.claw4j.capability.builtin.agenttools;

import io.github.claw4j.capability.untrusted.UntrustedToolCallback;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.github.claw4j.domain.untrusted.ExternalContent;
import org.springaicommunity.agent.tools.BraveWebSearchTool;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认工具：{@code Read} / {@code Grep} / {@code Glob} / {@code TodoWrite} / {@code WebSearch}
 * （{@code Write} / {@code Edit} 要开，{@code WebFetch} 在 agent 模块，见下）。
 *
 * <p>工具实现一行都不写，全部是 {@code spring-ai-agent-utils} 的类：读文件、按正则搜、按 glob 找、
 * 记一份计划、搜一次网页，这五件是"什么 agent 都会用一次"的操作。自己写一遍只会多出五个与库
 * 描述不同步的 Schema。
 *
 * <p>为什么没有 {@code enabled} 总开关：这一层没有任何副作用能超过它上面的 {@code Bash}（那条
 * 已经默认 {@code mode=full}），再加一个"要不要给 agent 眼睛"的开关，等于让默认值决定它是不是瞎子。
 * 真正该收紧的是档位与授权表，不是这里。
 *
 * <p>{@code WebFetch} 不在这个类里：它要一个 {@code ChatClient} 提炼抓回来的内容，而 {@code ChatClient}
 * 是 agent 模块造的。工具属于能力层、但它的依赖在上面，只能分开装（见
 * {@code WebFetchAutoConfiguration}）。
 */
@AutoConfiguration
@EnableConfigurationProperties({FileToolsProperties.class, WebToolsProperties.class})
public class AgentToolsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentToolsAutoConfiguration.class);

    /**
     * 只读三件套。名字是库的 {@code @Tool(name = …)} 定的，改不了；写在这里是为了权限与档位
     * 两处登记共用一份清单——分开写两份字面量，改一处漏一处的表现是启动失败并点名。
     */
    public static final List<String> READ_ONLY_TOOL_NAMES = List.of("Read", "Grep", "Glob");

    /** 会改磁盘的两个。默认不导出，见 {@code claw4j.tools.files.write-enabled}。 */
    public static final List<String> WRITE_TOOL_NAMES = List.of("Write", "Edit");

    /** 外置计划。它不碰文件也不出网。 */
    public static final String TODO_TOOL_NAME = "TodoWrite";

    /** 联网搜索。没配 key 时不存在。 */
    public static final String SEARCH_TOOL_NAME = "WebSearch";

    /** 读服务器上的文件。与 {@code read}（读业务表）是两个口径：拿 {@code read} 不该顺带能读磁盘。 */
    public static final String ACTION_READ_FILES = "read_files";

    /** 改服务器上的文件。比 {@link #ACTION_READ_FILES} 重一档，所以是独立动作。 */
    public static final String ACTION_WRITE_FILES = "write_files";

    /** 记一份计划。它不碰任何外部东西，只影响模型自己的步骤。 */
    public static final String ACTION_PLAN = "plan";

    /** 搜一次网页。出网，与 {@code read} 不是一个口径。 */
    public static final String ACTION_WEB_SEARCH = "web_search";

    /**
     * 文件工具。一个对象上挂着三个 {@code @Tool}（Read / Write / Edit），拆不开——所以下面
     * 按名字从导出结果里筛，同一份实例外提供两个 provider。
     *
     * @param properties 边界目录
     * @param webTools 联网配置，只用于把"装没装搜索"写进同一行日志
     * @return 三个动作都在的实例
     */
    @Bean
    @ConditionalOnMissingBean
    public FileSystemTools fileSystemTools(FileToolsProperties properties, WebToolsProperties webTools) {
        Path root = properties.absoluteDirectory();
        // 边界目录进启动日志：它决定了模型能看哪里，读不到文件时第一个要核对的就是这一行。
        log.info("文件工具的边界目录：{}；Write / Edit {}；WebSearch {}", root,
                properties.writeEnabled() ? "已开" : "未开（claw4j.tools.files.write-enabled）",
                webTools.hasSearchApiKey() ? "已装" : "未配 key，未装");
        return FileSystemTools.builder().allowedDirectory(root).build();
    }

    /**
     * 按正则搜文件内容。
     *
     * <p>工作目录与允许目录都给同一个根：前者让模型可以写相对路径，后者负责把 {@code ..} 与
     * 软链接跳出去的路径拒掉。
     *
     * @param properties 边界目录
     * @return 搜索工具
     */
    @Bean
    @ConditionalOnMissingBean
    public GrepTool grepTool(FileToolsProperties properties) {
        Path root = properties.absoluteDirectory();
        return GrepTool.builder().workingDirectory(root).allowedDirectory(root).build();
    }

    /**
     * 按 glob 找文件名。
     *
     * @param properties 边界目录
     * @return 文件名检索工具
     */
    @Bean
    @ConditionalOnMissingBean
    public GlobTool globTool(FileToolsProperties properties) {
        Path root = properties.absoluteDirectory();
        return GlobTool.builder().workingDirectory(root).allowedDirectory(root).build();
    }

    /**
     * 只读三件套。
     *
     * <p>从 {@code FileSystemTools} 导出的三个里只留 {@code Read}：这里放开的是"看"，不是"改"。
     *
     * @param files 文件工具实例
     * @param grep 内容检索
     * @param glob 文件名检索
     * @return 只含 {@code Read} / {@code Grep} / {@code Glob} 的 provider
     */
    @Bean
    public ToolCallbackProvider readFileTools(FileSystemTools files, GrepTool grep, GlobTool glob) {
        ToolCallbackProvider exported = MethodToolCallbackProvider.builder()
                .toolObjects(files, grep, glob)
                .build();
        return subset(exported, READ_ONLY_TOOL_NAMES);
    }

    /**
     * 写工具，默认不存在。
     *
     * <p>开关只有一个键（{@code write-enabled}），没有另外的 {@code enabled}：两个开关管同一件事
     * 迟早出现"开了但没生效"要人猜。
     *
     * @param files 文件工具实例
     * @return 只含 {@code Write} / {@code Edit} 的 provider
     */
    @Bean
    @ConditionalOnProperty(prefix = "claw4j.tools.files", name = "write-enabled")
    public ToolCallbackProvider writeFileTools(FileSystemTools files) {
        ToolCallbackProvider exported = MethodToolCallbackProvider.builder().toolObjects(files).build();
        return subset(exported, WRITE_TOOL_NAMES);
    }

    /**
     * 计划工具。
     *
     * <p>不挂开关：库的默认 {@code TodoEventHandler} 只打一行 debug，不落盘、不出网、不共享状态
     * （每次调用都是"模型把整份计划交上来"，工具自己不记）。没有可收紧的东西。
     *
     * @return 只含 {@code TodoWrite} 的 provider
     */
    @Bean
    public ToolCallbackProvider todoWriteTools() {
        return MethodToolCallbackProvider.builder().toolObjects(TodoWriteTool.builder().build()).build();
    }

    /**
     * 联网搜索。配了 key 才有。
     *
     * @param properties 搜索凭据
     * @return 只含 {@code WebSearch} 的 provider
     */
    @Bean
    @Conditional(SearchKeyPresent.class)
    public ToolCallbackProvider webSearchTools(WebToolsProperties properties) {
        BraveWebSearchTool tool = BraveWebSearchTool.builder(properties.searchApiKey()).build();
        ToolCallback[] exported = MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks();
        // 搜索结果也包边界，但不带警告句——与 OpenClaw 的 wrapWebContent(content, "web_search") 一致：
        // 它的 includeWarning 只在 web_fetch 时为 true。搜索结果是一小段摘要，边界足够说明来源。
        return ToolCallbackProvider.from(Arrays.stream(exported)
                .map(callback -> UntrustedToolCallback.wrapping(callback,
                        ExternalContent.Source.WEB_SEARCH, false))
                .toList());
    }

    /**
     * 权限登记。
     *
     * <p>只登记这次真的导出去了的名字：没导出的工具也登记一条，会让"授权表里有它"与"模型看得见它"
     * 变成两件事，而排查的人只能靠猜。
     *
     * @param files 文件工具配置，决定写工具在不在
     * @param web 联网配置，决定搜索在不在
     * @return 这一层的权限表
     */
    @Bean
    public ToolPermissionRegistry agentToolsPermissionRegistry(FileToolsProperties files,
                                                              WebToolsProperties web) {
        Map<String, ToolPermission> permissions = new LinkedHashMap<>();
        READ_ONLY_TOOL_NAMES.forEach(name -> permissions.put(name, ToolPermission.of(ACTION_READ_FILES)));
        permissions.put(TODO_TOOL_NAME, ToolPermission.of(ACTION_PLAN));
        if (web.hasSearchApiKey()) {
            permissions.put(SEARCH_TOOL_NAME, ToolPermission.of(ACTION_WEB_SEARCH));
        }
        if (files.writeEnabled()) {
            WRITE_TOOL_NAMES.forEach(name -> permissions.put(name, ToolPermission.of(ACTION_WRITE_FILES)));
        }
        return ToolPermissionRegistry.of(permissions);
    }

    /**
     * 档位：文件与联网都是 {@link ToolTier#CODING}，计划是 {@link ToolTier#MINIMAL}。
     *
     * <p>文件与联网不放 {@code MESSAGING}：那一档的承诺是"只做消息与业务只读"，而读服务器磁盘、
     * 往互联网 GET 一个 URL 都不是这条线上的事。放错档的表现是"只想让它答消息的部署"忽然能读
     * 到部署目录里的 `.env`。
     *
     * <p>{@code TodoWrite} 放 {@code MINIMAL}：它只影响模型自己怎么排步骤，不碰任何外部东西，
     * 连"只做聊天"的部署也用得上。
     *
     * @param files 文件工具配置，决定写工具在不在
     * @param web 联网配置，决定搜索在不在
     * @return 这一层的档位表
     */
    @Bean
    public ToolTierRegistry agentToolsTierRegistry(FileToolsProperties files, WebToolsProperties web) {
        Map<String, ToolTier> tiers = new LinkedHashMap<>();
        READ_ONLY_TOOL_NAMES.forEach(name -> tiers.put(name, ToolTier.CODING));
        tiers.put(TODO_TOOL_NAME, ToolTier.MINIMAL);
        if (web.hasSearchApiKey()) {
            tiers.put(SEARCH_TOOL_NAME, ToolTier.CODING);
        }
        if (files.writeEnabled()) {
            WRITE_TOOL_NAMES.forEach(name -> tiers.put(name, ToolTier.CODING));
        }
        return ToolTierRegistry.of(tiers);
    }

    /**
     * 按名字从一个已导出的 provider 里挑出要的那几个。
     *
     * <p>{@code MethodToolCallbackProvider} 只能按对象导出，同一个对象上的三个 {@code @Tool} 分不开。
     * 在这里筛而不是写三个包装对象：包一层就得自己维护一份 Schema，而那正是"用库"要避免的东西。
     *
     * @param exported 库里导出的全部工具
     * @param names 要留下的名字
     * @return 只含这些名字的 provider
     */
    private static ToolCallbackProvider subset(ToolCallbackProvider exported, List<String> names) {
        ToolCallback[] kept = Arrays.stream(exported.getToolCallbacks())
                .filter(callback -> names.contains(callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
        return ToolCallbackProvider.from(kept);
    }

    /**
     * 有没有搜索凭据。
     *
     * <p>不用 {@code @ConditionalOnProperty}：那个注解把"键存在且不等于 false"当满足条件，而
     * {@code ${BRAVE_API_KEY:}} 展开成空串仍然"存在"，于是在没配 key 的部署里会走到
     * {@code builder("")} 的断言上，启动直接失败。
     */
    static final class SearchKeyPresent implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("claw4j.tools.web.search-api-key");
            return key != null && !key.isBlank();
        }
    }
}
