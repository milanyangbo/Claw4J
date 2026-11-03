package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.advisor.PiiRedactionAdvisor;
import io.github.claw4j.agent.approval.ApprovalGate;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.observability.MeteredToolCallback;
import io.github.claw4j.agent.persona.AgentContextAdvisor;
import io.github.claw4j.agent.persona.AgentProperties;
import io.github.claw4j.agent.persona.BuiltinPersona;
import io.github.claw4j.agent.run.RunBudget;
import io.github.claw4j.agent.run.RunDelivery;
import io.github.claw4j.agent.run.RunStore;
import io.github.claw4j.agent.session.SessionCompactor;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolProfile;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.github.claw4j.persona.Persona;
import io.github.claw4j.persona.PersonaCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.claw4j.agent.model.ModelConfiguration;

/**
 * Agent 的最小装配：一个系统提示词加一条 Advisor 链。
 *
 * <p>Advisor 链是 2.x 的统一抽象，鉴权已在其上（M1-03），脱敏与记忆后续也挂在这里
 * （ADR-0007）。装配点已经固定，后续加 Advisor 只是加一个 bean，不需要改调用方。
 *
 * <p>注册为自动配置而不是显式 @Configuration，是为了让其它模块引入依赖即可生效，
 * 不必要求调用方把 io.github.claw4j.agent 加进组件扫描路径。
 */
@AutoConfiguration
@EnableConfigurationProperties({ToolSearchProperties.class, ToolsProperties.class, AgentProperties.class})
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    /**
     * 这次生效的人设：配了目录就从目录里选，没配就用内置的那份最小人设。
     *
     * <p>它在装配期定一次，这是有意的——"换一个人设"要重启，与 P09 换档位同一条理由：
     * 热切换会让"已经在跑的 run"和"新配置"出现两种真相，恢复时读哪一份说不清。
     *
     * <p>但**内容**（时间那一行）不能在这里定死：那是 {@link AgentContextAdvisor} 每轮算的。
     * 两件事分开之后，"人设是配置"与"时间是活的"各自成立，不会互相绑住。
     */
    @Bean
    public PersonaCatalog.Catalog personaCatalog(AgentProperties agent) {
        if (!agent.hasDirectory()) {
            return PersonaCatalog.single(BuiltinPersona.get(), List.of());
        }
        return PersonaCatalog.load(Path.of(agent.directory()), agent.defaultName());
    }

    /**
     * 每轮拼系统提示词的 Advisor。
     *
     * <p>它是个普通 Advisor bean，{@link #chatClient} 里那条
     * {@code ObjectProvider<Advisor>} 会自动收进去——不需要在装配方法里点名。
     * 这正是 ADR-0007 说的"加一条能力只是加一个 bean"。
     */
    @Bean
    public AgentContextAdvisor agentContextAdvisor(PersonaCatalog.Catalog personas, AgentProperties agent) {
        // Clock 必须带时区：容器里默认通常是 UTC，晚上八点之后"今天"在模型那里是明天。
        return new AgentContextAdvisor(personas.selected(), personas.glossary(), Clock.system(agent.zone()));
    }

    /**
     * 具体用哪个 ChatModel 由 ModelConfiguration 决定：有 key 装真实模型，
     * 一个都没有就启动失败（ADR-0011，没有 mock 兜底）。这里只管装配，不关心模型从哪来。
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(ChatModel chatModel,
                                 ObjectProvider<Advisor> advisors,
                                 ObjectProvider<ToolCallback> toolCallbacks,
                                 ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                                 ObjectProvider<ObservationRegistry> observationRegistry,
                                 ObjectProvider<MeterRegistry> meterRegistry,
                                 ObjectProvider<RunBudget> runBudget,
                                 ObjectProvider<ToolCallAudit> audit,
                                 ObjectProvider<ToolTierRegistry> tierRegistries,
                                 ToolIndex toolIndex,
                                 ToolSearchProperties toolSearch,
                                 ToolsProperties toolPolicy,
                                 AgentProperties agent,
                                 PersonaCatalog.Catalog personas) {
        ObservationRegistry observations = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
        ToolProfile profile = profileOf(personas.selected(), toolPolicy);
        List<ToolCallback> tools = collectToolCallbacks(toolCallbacks, toolCallbackProviders,
                meterRegistry.getIfAvailable(), runBudget.getIfAvailable(), audit.getIfAvailable(),
                profile, tierRegistries.orderedStream().toList());

        // 工具循环的 manager 必须显式塞进来：走单参的 ChatClient.builder(chatModel)
        // 拿到的是框架内建的那个，用的是 40/150 的默认值，这里定的预算根本不生效
        // （细节见 ToolCallBudget 的类注释）。预算只有一个落点：ToolSearch.toolLoop。
        //
        // ToolIndex 是必填参数而不是 ObjectProvider：循环只有"检索"这一种，
        // 没有索引时唯一的出路是启动就失败，而不是悄悄退化成全塞。
        var toolCallingAdvisor = ToolSearch.toolLoop(toolSearch, toolIndex, observations);

        // "这次用的是哪份人设、档位来自哪一层"必须能从日志里读出来。
        // 内置默认尤其要说：一份谁也没注意到的默认人设，比一份写错的配置更难查——
        // 前者没有任何报错，只是说话的方式不对。
        log.info("这个部署的 agent 是 {}（{}），人设来自 {}；时区 {}；工具档位 {}（来自 {}）",
                personas.selected().name(), personas.selected().description(),
                agent.hasDirectory() ? agent.directory() : "内置默认（未配 claw4j.agent.directory）",
                agent.zone(), profile.tier(),
                personas.selected().tier().isPresent() ? "人设文件的 tier" : "claw4j.tools.profile");

        return ChatClient.builder(chatModel, observations, null, null, toolCallingAdvisor)
                // 没有 defaultSystem：系统提示词由 AgentContextAdvisor 每轮拼（人设 + 口径 + 现在的时间）。
                // 放在这里的话，时间会在装配那一刻定死，进程跑一周还是启动那天。
                .defaultAdvisors(advisors.orderedStream().toList())
                // 工具同理：容器里所有来源都挂上，这里不点名任何工具类。
                // 没有工具时是空数组，工具循环照常工作（只是无工具可检索）。
                // 这份清单永远不会被整份塞进提示词：工具循环先把它们索引进 ToolIndex，
                // 只把"找工具"那一个交给模型，查到谁再注入谁。
                .defaultToolCallbacks(tools)
                .build();
    }

    /**
     * 档位从哪来：人设写了就用它的，没写就用全局那份。
     *
     * <p>只覆盖档位、不覆盖 allow / deny：前者是"这个助手允许多宽"（销售助手永远不该跑脚本，
     * 哪怕部署的全局档位是 full），后者是"这套部署额外排除什么"，属于部署级的事实。
     * 让 allow / deny 跟着 agent 走的话，"同一个助手换个部署就少了几个工具"，
     * 而那正是 per-agent 配置最容易变得说不清的地方。
     */
    private static ToolProfile profileOf(Persona persona, ToolsProperties tools) {
        ToolProfile global = tools.toProfile();
        return persona.tier()
                .map(tier -> new ToolProfile(tier, global.allow(), global.deny()))
                .orElse(global);
    }

    /**
     * 工具检索用的索引。默认是内存里的正则索引：把查询切成关键词，在工具名与描述里匹配。
     *
     * <p>选它而不是向量索引（{@code VectorToolIndex}）是因为这一版的判据是"提示词里的工具定义 token
     * 降下来、选工具还不选错"，正则索引已经够回答这个问题，而且不需要 embedding 调用、
     * 不新增一张表。换成 pgvector 时替换这个 bean 即可——装配那边只认 {@link ToolIndex}。
     */
    @Bean
    @ConditionalOnMissingBean(ToolIndex.class)
    public ToolIndex toolIndex() {
        return new RegexToolIndex();
    }

    /**
     * 检索工具自己的权限。
     *
     * <p>它是一层"找工具"的元工具：碰不到任何表、也不执行业务动作，所以只要有 {@code read}
     * 动作就放行，不为此新增一个动作名（新增动作名意味着所有角色配置都要改一遍才能检索）。
     *
     * <p><b>检索不等于授权</b>：它返回的只是工具名。模型拿到名字之后真调哪一个，
     * 仍然逐个过 {@link AuthorizationAdvisor} 的登记表——查得到但调不动，是这条链上正确的姿势。
     */
    @Bean
    public ToolPermissionRegistry toolSearchPermission() {
        return ToolPermissionRegistry.of(Map.of(ToolSearch.TOOL_NAME, ToolPermission.read()));
    }

    /**
     * 检索工具的档位：{@link ToolTier#MINIMAL}。
     *
     * <p><b>它必须在每一档都出现</b>：清单里其余工具都是"按需检索才看得到"的，
     * 把检索工具自己挡掉之后，模型面前就是一个空清单，表现出来是"它什么都不肯做"。
     * 这也是 {@link ToolProfile#select} 那条"筛空即失败"兜住的情况——但能从源头避免就不该靠兜底。
     */
    @Bean
    public ToolTierRegistry toolSearchTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(ToolSearch.TOOL_NAME), ToolTier.MINIMAL);
    }

    /**
     * 把容器里所有工具来源收成一个列表。
     *
     * <p><b>两种注册形态都要收</b>：{@code ToolCallbackProvider} 导出"一组工具"，
     * 单个 {@code ToolCallback} 只导出"一个工具"。只收 provider 的那版会让
     * 单工具形态**静默不进清单**——不报错、不告警，只是模型看不见那个工具，
     * 直到有人问"为什么它不知道有这个能力"。
     *
     * <p>委派那一层（{@code SubagentAutoConfiguration}）也用这一个方法
     * 给子 Agent 凑工具集：那里漏收一种形态的表现是"子 Agent 白名单点名了却说没有这个工具"，
     * 是同一个坑的第三次出现。收工具这件事只该有一处实现。
     *
     * <p>顺序不影响行为：工具是按名字被模型和鉴权认领的，清单里谁先谁后都一样。
     * 重名不在这里去重——两个来源导出同名工具是配置错误，该在清单断言上红，
     * 而不是被这里悄悄吞掉一个。
     *
     * <p><b>计量与扣预算也在这里包</b>：这是所有工具的**唯一入口**（父 Agent 与子 Agent 都走它），
     * 包在这一层就不会出现"某条路径上的工具调用没被记上、也没被扣"。
     * 预算按 run 累计（{@link RunBudget}，A04），而 runId 由入口放进工具上下文——
     * 也就是说"扣谁的额度"这件事在这里才第一次知道答案。
     *
     * <p><b>审计也在这里包</b>（P07）：它要的是同一个事实——"这次调用真的发生了"。
     * 放在这里而不是每个工具自己写，理由与预算相同：漏一条路径的表现是
     * "有些调用在审计里不存在"，而那正是审计最不该有的样子。
     *
     * @param budget 可以为 null：没有装配 run 存储时（{@code claw4j.run.enabled=false}）
     *               工具照常调用，只是这一轮没有额度可扣
     * @param audit  可以为 null：同上，没有 run 存储就没有可写的审计落点
     * @param profile 这个部署的工具策略（P09）。**必填**：给它一个"不过滤"的默认值等于让
     *               某条装配路径漏传参数时静默全开，而这条路径正是父子两个 Agent 共用的那一条
     * @param tierRegistries 各模块声明的档位登记表。查不到档位的工具会让这次收集直接失败
     */
    public static List<ToolCallback> collectToolCallbacks(ObjectProvider<ToolCallback> callbacks,
                                                          ObjectProvider<ToolCallbackProvider> providers,
                                                          MeterRegistry registry,
                                                          RunBudget budget,
                                                          ToolCallAudit audit,
                                                          ToolProfile profile,
                                                          List<ToolTierRegistry> tierRegistries) {
        List<ToolCallback> collected = new ArrayList<>();
        callbacks.orderedStream().forEach(collected::add);
        providers.orderedStream()
                .forEach(provider -> collected.addAll(List.of(provider.getToolCallbacks())));

        // 先筛再包（P09）：被挡掉的工具不该连包装对象都造出来。筛的是名字，
        // 所以重名不会被这里合并——重名是配置错误，该在清单断言上红。
        ToolProfile.Selection selection = profile.select(
                collected.stream().map(callback -> callback.getToolDefinition().name()).toList(),
                tierRegistries);
        report(profile, selection);
        Set<String> kept = Set.copyOf(selection.kept());

        // 一律包一层：没有 registry 时它只记"这一轮调过哪些工具"，不报指标。
        // 语义缓存判断副作用靠的就是这份记录，所以它不能跟着 actuator 一起缺席。
        return collected.stream()
                .filter(callback -> kept.contains(callback.getToolDefinition().name()))
                // 显式转型，否则 stream 的元素类型被推断成 MeteredToolCallback，
                // 与返回类型 List<ToolCallback> 对不上（它不是泛型类，推断不出父类型）。
                .map(callback -> (ToolCallback) new MeteredToolCallback(callback, registry, budget, audit))
                .toList();
    }

    /**
     * 档位与清单的落点必须留痕：出了问题第一个要回答的是"这个部署到底开了什么"。
     *
     * <p>只在真的挡掉东西时打 INFO——默认档下每次启动都刷一行"挡掉 0 个"，
     * 于是真正的告警会淹没在同一句话里。
     *
     * <p>`unused` 用 WARN 单独打：它既可能是"这个部署没装那个模块"（正常），
     * 也可能是"配置里拼错了"（不正常）。两者长得一样，所以列出来让人自己看，
     * 但**不能因此启动失败**——裁剪部署时针对某个模块的 deny 条目本来就该落空。
     */
    private static void report(ToolProfile profile, ToolProfile.Selection selection) {
        if (selection.droppedAnything()) {
            log.info("工具档位 {}：清单 {} 个，挡掉 {} 个（档位 {} / allow {} / deny {}）",
                    profile.tier(), selection.kept().size(),
                    selection.byTier().size() + selection.byAllow().size() + selection.byDeny().size(),
                    selection.byTier(), selection.byAllow(), selection.byDeny());
        }
        if (!selection.unused().isEmpty()) {
            log.warn("claw4j.tools 的 allow / deny 里提到了清单里不存在的工具 {}："
                    + "要么这个部署没装提供它的模块（正常），要么名字拼错了（不正常）——"
                    + "拼错的话那一条永远不生效。", selection.unused());
        }
    }


    /**
     * 鉴权 Advisor：容器里所有 {@link ToolPermissionRegistry} 都由它消费，
     * 也就是"工具需要什么权限"由提供工具的模块自己声明，Agent 不认识任何具体工具。
     * 一个都没声明时它照样在链上，只是每个工具都因"未登记权限"被拒——
     * 失败的姿势是拒绝而不是放行（ADR-0007）。
     *
     * <p><b>审批门走 {@code ObjectProvider}</b>（P01、P02）：它跟着 run 存储一起装配
     * （审批表与 run 在同一个 schema 下），没有数据库的部署里它是缺席的。
     * 缺席时用 {@link ApprovalGate#denyAll()}——标了要审批的动作照样被拦，
     * 而不是因为少一个 bean 悄悄变成普通动作。
     *
     * <p><b>审计同理</b>（P07）：它也跟着 run 存储装配。缺席时判定照常，
     * 只是事后查不到这一次——被拒与等审批那两行没有落点。
     */
    @Bean
    @ConditionalOnMissingBean(AuthorizationAdvisor.class)
    public AuthorizationAdvisor authorizationAdvisor(ObjectProvider<ToolPermissionRegistry> registries,
                                                     ObjectProvider<ApprovalGate> gates,
                                                     ObjectProvider<MeterRegistry> meterRegistry,
                                                     ObjectProvider<ToolCallAudit> audit) {
        return new AuthorizationAdvisor(registries.orderedStream().toList(),
                gates.getIfAvailable(ApprovalGate::denyAll), meterRegistry.getIfAvailable(),
                audit.getIfAvailable());
    }

    /**
     * 出站脱敏：order 排在工具循环**外面**（见 {@link PiiRedactionAdvisor#ORDER}），
     * 整通对话只在出站前做一次。它不认识任何具体通道，也不改调用方——只是多加一个 bean。
     */
    @Bean
    @ConditionalOnMissingBean(PiiRedactionAdvisor.class)
    public PiiRedactionAdvisor piiRedactionAdvisor() {
        return new PiiRedactionAdvisor();
    }

    /**
     * 请求与响应的整份 dump。**默认什么都不打**：框架实现里每个方法体都包在
     * {@code logger.isDebugEnabled()} 里，INFO 级别下连字符串拼接都不发生。
     *
     * <p><b>order 用框架默认的 0</b>，也就是排在工具循环内部、紧贴模型调用。
     * 官方参考文档里这个 Advisor 的实现示例就是 {@code getOrder() { return 0; }}，
     * 按官方推荐的落点，它看到的是每一轮真正发给模型的东西（含工具返回的行）
     * 与模型的原始回复。想改成"只看用户最终看到的那一份"，得排到脱敏外层
     * （order 小于 {@link PiiRedactionAdvisor#ORDER}），代价是看不到工具往返。
     *
     * <p><b>打开它之前先想清楚 PII</b>：它 dump 的是模型入口与出口的原文，
     * 而脱敏在它外层，所以日志里是**未脱敏**的手机号与原始数据行。
     * 这也意味着它必须留在 DEBUG：{@code logging.level.org.springframework.ai.chat.client.advisor}
     * 一旦在某个环境里被调到 DEBUG，会话明文就落在日志里了。
     */
    @Bean
    @ConditionalOnMissingBean(SimpleLoggerAdvisor.class)
    public SimpleLoggerAdvisor simpleLoggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }

    /**
     * Agent 对外的门，通道只认这个类型（ADR-0010）。
     * 注册在这里意味着任何模块只要拿到 AgentPort 就能驱动 Agent，不需要知道 ChatClient 的存在。
     */
    @Bean
    @ConditionalOnMissingBean(AgentPort.class)
    public AgentPort agentPort(ChatClient chatClient,
                               ObjectProvider<SessionCompactor> compactor,
                               ObjectProvider<RunStore> runs,
                               ObjectProvider<RunBudget> budget,
                               ObjectProvider<RunDelivery> delivery) {
        // run、预算、投递都走 ObjectProvider 而不是直接注入：没配存储时它们是缺席而不是失败，
        // 门照常住着，只是"这件事做完了没有"没人记、也没人在看额度（与压缩器同一个判断）。
        // 三者同进同出：预算与收件地址都是 run 的属性，没有 run 就没有可扣的额度、
        // 也没有"送到哪儿"。
        return new ChatClientAgentPort(chatClient, compactor.getIfAvailable(), runs.getIfAvailable(),
                budget.getIfAvailable(), delivery.getIfAvailable());
    }
}
