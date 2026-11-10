package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.advisor.PiiRedactionAdvisor;
import io.github.claw4j.agent.approval.ApprovalGate;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.observability.MeteredToolCallback;
import io.github.claw4j.agent.observability.SpanTracer;
import io.github.claw4j.agent.persona.AgentContextAdvisor;
import io.github.claw4j.agent.persona.AgentProperties;
import io.github.claw4j.agent.persona.BuiltinPersona;
import io.github.claw4j.agent.channel.ConversationDelivery;
import io.github.claw4j.agent.session.SessionEntries;
import io.github.claw4j.agent.session.SessionCompactor;
import io.github.claw4j.capability.tool.ToolCatalog;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import io.github.claw4j.agent.model.ModelConfiguration;

/**
 * Agent 的最小装配：一个系统提示词加一条 Advisor 链。
 *
 * <p>Advisor 链是 2.x 的统一抽象，鉴权已在其上（M1-03），脱敏与记忆后续也挂在这里（ADR-0007）。
 * 装配点固定之后，加一条能力只是加一个 bean，不需要改调用方。
 *
 * <p>注册为自动配置而不是显式 {@code @Configuration}，是为了让其它模块引入依赖即可生效，
 * 不必要求调用方把 {@code io.github.claw4j.agent} 加进组件扫描路径。
 */
@AutoConfiguration
@EnableConfigurationProperties({ToolSearchProperties.class, ToolsProperties.class, AgentProperties.class})
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    /**
     * 这次生效的人设：配了目录就从目录里选，没配就用内置的那份最小人设。
     *
     * <p>它在装配期定一次，这是有意的：换一个人设要重启，与 P09 换档位同一条理由——热切换会让
     * 已经在跑的 run 与新配置出现两种真相，恢复时读哪一份说不清。
     *
     * <p>但内容（时间那一行）不在这里定死，那是 {@link AgentContextAdvisor} 每轮算的。两件事
     * 分开之后，「人设是配置」与「时间是活的」各自成立。
     *
     * @param agent agent 配置，取人设目录与默认名
     * @return 人设目录，选中项已定
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
     * <p>它是普通 Advisor bean，{@link #chatClient} 里那条 {@code ObjectProvider<Advisor>}
     * 会自动收进去，不需要在装配方法里点名。这正是 ADR-0007 说的「加一条能力只是加一个 bean」。
     *
     * @param personas 人设目录
     * @param agent agent 配置，取时区
     * @return 人设与口径的拼装 Advisor
     */
    @Bean
    public AgentContextAdvisor agentContextAdvisor(PersonaCatalog.Catalog personas, AgentProperties agent) {
        // Clock 必须带时区：容器里默认通常是 UTC，晚上八点之后"今天"在模型那里是明天。
        return new AgentContextAdvisor(personas.selected(), personas.glossary(), Clock.system(agent.zone()));
    }

    /**
     * 这个部署的工具清单，以及它是按哪一档筛出来的（P11 起有两个消费者）。
     *
     * <p>清单提成 bean，而不是留在 {@link #chatClient} 里的局部变量：第二个出口（暴露成 MCP
     * server）出现之后，两条路各收一遍工具，等于把 P09 的档位、计量包装、权限登记各自再实现一遍，
     * 漂了也不会报错——外部客户端会看到一个看起来正常、其实绕过了三道门的清单。两个出口吃同一份，
     * 才能把「外部看到的」写成「模型能用的」子集，一份可以断言，两份各自算就不行。
     *
     * <p>给出去的是活视图（票 03）：每次 {@code tools()} 都重新收一遍来源。MCP 那一层按 server
     * 记状态、连不上就后台退避重试，这一轮看得见哪些只能到那时才知道；装配期拍下的快照会让恢复之后
     * 的能力永远回不来。两处日志（档位落点、MCP 恢复）因此按「结论变了没有」去重，见
     * {@code signatureOf}。
     *
     * @param toolCallbacks 单个工具形态的来源
     * @param toolCallbackProviders 成组工具形态的来源
     * @param meterRegistry 指标注册表，可能不存在
     * @param audit 审计接缝，可能不存在
     * @param tracer 埋点入口，可能不存在
     * @param tierRegistries 各模块声明的档位登记表
     * @param toolPolicy 全局档位策略；人设文件里的 {@code tier} 覆盖它（只覆盖档位，不覆盖
     *     allow / deny）
     * @param personas 人设目录，取选中人设的名字与档位
     * @return 工具清单
     */
    @Bean
    public ToolCatalog toolCatalog(ObjectProvider<ToolCallback> toolCallbacks,
                                   ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                                   ObjectProvider<MeterRegistry> meterRegistry,
                                   ObjectProvider<ToolCallAudit> audit,
                                   ObjectProvider<SpanTracer> tracer,
                                   ObjectProvider<ToolTierRegistry> tierRegistries,
                                   ToolsProperties toolPolicy,
                                   PersonaCatalog.Catalog personas) {
        ToolProfile profile = profileOf(personas.selected(), toolPolicy);
        // 结论变了才打日志（见 signatureOf）：清单现在是每轮解析的，每轮都打一行
        // "挡掉 0 个"会把真正的告警淹掉，而"清单变了"本身是值得留一行的事件。
        AtomicReference<String> reported = new AtomicReference<>();
        return new ToolCatalog(personas.selected().name(), profile, () ->
                collectToolCallbacks(toolCallbacks, toolCallbackProviders,
                        meterRegistry.getIfAvailable(), audit.getIfAvailable(),
                        tracer.getIfAvailable(() -> SpanTracer.NONE), profile,
                        tierRegistries.orderedStream().toList(),
                        selection -> {
                            String signature = signatureOf(selection);
                            if (!signature.equals(reported.getAndSet(signature))) {
                                report(profile, selection);
                            }
                        }));
    }

    /**
     * 对话客户端，装配工具循环、Advisor 链与工具来源。
     *
     * <p>用哪个 ChatModel 由 {@code ModelConfiguration} 决定：有 key 装真实模型，一个都没有就启动
     * 失败（ADR-0011，没有 mock 兜底）。这里只管装配，不关心模型从哪来。
     *
     * <p>工具来自 {@link #toolCatalog}，不在这里再收一遍：那个 bean 同时也是 MCP server 的工具
     * 来源，两处各收一次就是两份清单，而两份一定会漂。
     *
     * @param chatModel 模型客户端
     * @param advisors 容器里所有 Advisor，按 order 挂上
     * @param observationRegistry 观测注册表，可能不存在
     * @param catalog 工具清单
     * @param toolIndex 检索索引
     * @param toolSearch 检索配置
     * @param agent agent 配置，取工具调用上限
     * @param personas 人设目录，只用于启动日志
     * @return 建好的 {@code ChatClient}
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(ChatModel chatModel,
                                 ObjectProvider<Advisor> advisors,
                                 ObjectProvider<ObservationRegistry> observationRegistry,
                                 ToolCatalog catalog,
                                 ToolIndex toolIndex,
                                 ToolSearchProperties toolSearch,
                                 AgentProperties agent,
                                 PersonaCatalog.Catalog personas) {
        ObservationRegistry observations = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);

        // 工具循环的 manager 必须显式塞进来：走单参的 ChatClient.builder(chatModel)
        // 拿到的是框架内建的那个，用的是 40/150 的默认值，这里定的预算根本不生效
        // （细节见 ToolCallBudget 的类注释）。预算只有一个落点：ToolSearch.toolLoop。
        //
        // ToolIndex 是必填参数而不是 ObjectProvider：循环只有"检索"这一种，
        // 没有索引时唯一的出路是启动就失败，而不是悄悄退化成全塞。
        var toolCallingAdvisor = ToolSearch.toolLoop(toolSearch, toolIndex, observations,
                agent.toolCallLimit());

        // "这次用的是哪份人设、档位来自哪一层"必须能从日志里读出来。
        // 内置默认尤其要说：一份谁也没注意到的默认人设，比一份写错的配置更难查——
        // 前者没有任何报错，只是说话的方式不对。
        log.info("这个部署的 agent 是 {}（{}），人设来自 {}；时区 {}；工具档位 {}（来自 {}）",
                personas.selected().name(), personas.selected().description(),
                agent.hasDirectory() ? agent.directory() : "内置默认（未配 claw4j.agent.directory）",
                agent.zone(), catalog.profile().tier(),
                personas.selected().tier().isPresent() ? "人设文件的 tier" : "claw4j.tools.profile");

        return ChatClient.builder(chatModel, observations, null, null, toolCallingAdvisor)
                // 没有 defaultSystem：系统提示词由 AgentContextAdvisor 每轮拼（人设 + 口径 + 现在的时间）。
                // 放在这里的话，时间会在装配那一刻定死，进程跑一周还是启动那天。
                .defaultAdvisors(advisors.orderedStream().toList())
                // 工具同理：容器里所有来源都挂上，这里不点名任何工具类。
                // 没有工具时是空数组，工具循环照常工作（只是无工具可检索）。
                // 这份清单永远不会被整份塞进提示词：工具循环先把它们索引进 ToolIndex，
                // 只把"找工具"那一个交给模型，查到谁再注入谁。
                //
                // 给它的是 provider，不是快照（票 03）：框架在每次请求时展开 provider
                // （DefaultChatClientUtils.toChatClientRequest 里那个循环），于是这一轮模型
                // 看得见哪些工具每轮重新算一次。写成 defaultToolCallbacks(catalog.tools())
                // 的话，MCP server 恢复之后连接是新的、清单还是启动那天那份，
                // 模型那边只会说「我没有这个能力」。
                .defaultToolCallbacks(catalog)
                .build();
    }

    /**
     * 档位从哪来：人设写了就用它的，没写就用全局那份。
     *
     * <p>只覆盖档位、不覆盖 allow / deny：前者是「这个助手允许多宽」（销售助手永远不该跑脚本，
     * 哪怕部署的全局档位是 full），后者是「这套部署额外排除什么」，属于部署级的事实。让
     * allow / deny 跟着 agent 走的话，同一个助手换个部署就少了几个工具，而那正是 per-agent
     * 配置最容易变得说不清的地方。
     *
     * @param persona 选中的人设
     * @param tools 全局工具策略
     * @return 生效的档位与收窄规则
     */
    private static ToolProfile profileOf(Persona persona, ToolsProperties tools) {
        ToolProfile global = tools.toProfile();
        return persona.tier()
                .map(tier -> new ToolProfile(tier, global.allow(), global.deny()))
                .orElse(global);
    }

    /**
     * 工具检索用的索引：把查询切成关键词，在工具名与描述里匹配。
     *
     * <p>用中文那一版而不是框架的 {@code RegexToolIndex}：后者只按空白与标点切词，中文整句进去
     * 是一个 token，当子串匹配等于什么都不匹配。代价不止命中率——用它就得反过来要求模型自己用
     * 空格分词，等于把实现的缺陷教给模型。{@link ChineseToolIndex} 让那段提示词没有必要存在
     * （见 {@link ToolSearch#SYSTEM_SUFFIX}）。
     *
     * <p>选它而不是向量索引 {@code VectorToolIndex}：这一版的判据是「提示词里的工具定义 token
     * 降下来、选工具还不选错」，词面匹配已经够回答这个问题，而且不需要 embedding 调用、
     * 不新增一张表。工具数到几百、需要 TF-IDF 与倒排索引时，要换的也只有这一个 bean，装配那边
     * 只认 {@link ToolIndex}。
     *
     * @return 中文工具索引
     */
    @Bean
    @ConditionalOnMissingBean(ToolIndex.class)
    public ToolIndex toolIndex() {
        return new ChineseToolIndex();
    }

    /**
     * 检索工具自己的权限。
     *
     * <p>它是一层「找工具」的元工具：碰不到任何表、也不执行业务动作，所以只要有 {@code read}
     * 动作就放行，不为此新增一个动作名（新增就意味着所有角色配置都要改一遍才能检索）。
     *
     * <p>检索不等于授权：它返回的只是工具名。模型拿到名字之后真调哪一个，仍然逐个过
     * {@link AuthorizationAdvisor} 的登记表。查得到但调不动，是这条链上正确的姿势。
     *
     * @return 只含检索工具一项的权限登记表
     */
    @Bean
    public ToolPermissionRegistry toolSearchPermission() {
        return ToolPermissionRegistry.of(Map.of(ToolSearch.TOOL_NAME, ToolPermission.read()));
    }

    /**
     * 检索工具的档位：{@link ToolTier#MINIMAL}。
     *
     * <p>它必须在每一档都出现：清单里其余工具都要检索才看得见，把检索工具自己挡掉之后，模型面前
     * 就是一个空清单，表现出来是「它什么都不肯做」。这也是 {@link ToolProfile#select} 那条
     * 「筛空即失败」兜住的情况——但能从源头避免就不该靠兜底。
     *
     * @return 检索工具的档位表
     */
    @Bean
    public ToolTierRegistry toolSearchTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(ToolSearch.TOOL_NAME), ToolTier.MINIMAL);
    }

    /**
     * 把容器里所有工具来源收成一个列表。
     *
     * <p>两种注册形态都要收：{@code ToolCallbackProvider} 导出「一组工具」，单个
     * {@code ToolCallback} 只导出「一个工具」。只收 provider 会让单工具形态静默不进清单——不报错、
     * 不告警，只是模型看不见那个工具。
     *
     * <p>委派那一层（{@code SubagentAutoConfiguration}）也用这一个方法给子 Agent 凑工具集：
     * 那里漏收一种形态的表现是「子 Agent 白名单点名了却说没有这个工具」。收工具只该有一处实现。
     *
     * <p>顺序不影响行为：工具按名字被模型和鉴权认领。重名不在这里去重——两个来源导出同名工具是
     * 配置错误，该在清单断言上红，而不是被这里悄悄吞掉一个。
     *
     * <p>计量、审计（P07）、trace（P14）都在这一层包：这是所有工具的唯一入口，父 Agent 与子 Agent
     * 都走它，包在这里就不会出现「某条路径上的工具调用没被记上」。tracer 必填而不是
     * {@code ObjectProvider}：给它一个「没有就不画」的默认值，某条装配路径漏传参数时会静默少一段，
     * 而那种缺失在图上与「这个工具没被调」完全一样。
     *
     * @param callbacks 单个工具形态的来源
     * @param providers 成组工具形态的来源
     * @param registry 指标注册表，可以为 null
     * @param audit 审计接缝，可以为 null：没有装配持久化那一层时没有可写的落点，工具照常调用
     * @param tracer 埋点入口。没开观测时传 {@link SpanTracer#NONE}，不要传 null 让它去猜
     * @param profile 这个部署的工具策略（P09），必填：给它一个「不过滤」的默认值，等于让某条装配
     *     路径漏传参数时静默全开，而这条路径正是父子两个 Agent 共用的那一条
     * @param tierRegistries 各模块声明的档位登记表。查不到档位的工具会让这次收集直接失败
     * @return 筛过、包过的工具列表
     */
    public static List<ToolCallback> collectToolCallbacks(ObjectProvider<ToolCallback> callbacks,
                                                          ObjectProvider<ToolCallbackProvider> providers,
                                                          MeterRegistry registry,
                                                          ToolCallAudit audit,
                                                          SpanTracer tracer,
                                                          ToolProfile profile,
                                                          List<ToolTierRegistry> tierRegistries) {
        return collectToolCallbacks(callbacks, providers, registry, audit, tracer, profile, tierRegistries,
                selection -> report(profile, selection));
    }

    /**
     * 同上，但把「这次筛完的结论」交给调用方处置。
     *
     * <p>多这个出口是因为清单变成每轮解析（票 03）：日志只能打一份，而「清单变了」与「每轮都
     * 一样」是两件不同的事。父链那条路按结论的签名去重（见 {@link #toolCatalog}），子链那条仍然
     * 每次委派报一次——它本来就是一个稀疏事件。
     *
     * @param callbacks 单个工具形态的来源
     * @param providers 成组工具形态的来源
     * @param registry 指标注册表，可以为 null
     * @param audit 审计接缝，可以为 null
     * @param tracer 埋点入口，没开观测时传 {@link SpanTracer#NONE}
     * @param profile 这个部署的工具策略（P09），必填
     * @param tierRegistries 各模块声明的档位登记表
     * @param onSelection 筛完之后的回调，拿得到留下了哪些、挡掉了哪些
     * @return 筛过、包过的工具列表
     */
    public static List<ToolCallback> collectToolCallbacks(ObjectProvider<ToolCallback> callbacks,
                                                          ObjectProvider<ToolCallbackProvider> providers,
                                                          MeterRegistry registry,
                                                          ToolCallAudit audit,
                                                          SpanTracer tracer,
                                                          ToolProfile profile,
                                                          List<ToolTierRegistry> tierRegistries,
                                                          Consumer<ToolProfile.Selection> onSelection) {
        List<ToolCallback> collected = new ArrayList<>();
        callbacks.orderedStream().forEach(collected::add);
        // provider 的 getToolCallbacks() 每次调用都重新问一次来源：MCP 那一层是活视图，
        // 恢复之后出现的工具从这里进来（票 03）。这里不能缓存它的结果。
        providers.orderedStream()
                .forEach(provider -> collected.addAll(List.of(provider.getToolCallbacks())));

        // 先筛再包（P09）：被挡掉的工具不该连包装对象都造出来。筛的是名字，
        // 所以重名不会被这里合并——重名是配置错误，该在清单断言上红。
        ToolProfile.Selection selection = profile.select(
                collected.stream().map(callback -> callback.getToolDefinition().name()).toList(),
                tierRegistries);
        onSelection.accept(selection);
        Set<String> kept = Set.copyOf(selection.kept());

        // 一律包一层：没有 registry 时它只记"这一轮调过哪些工具"，不报指标。
        // 语义缓存判断副作用靠的就是这份记录，所以它不能跟着 actuator 一起缺席。
        return collected.stream()
                .filter(callback -> kept.contains(callback.getToolDefinition().name()))
                // 显式转型，否则 stream 的元素类型被推断成 MeteredToolCallback，
                // 与返回类型 List<ToolCallback> 对不上（它不是泛型类，推断不出父类型）。
                .map(callback -> (ToolCallback) new MeteredToolCallback(callback, registry, audit, tracer))
                .toList();
    }

    /**
     * 档位与清单的落点留痕：出了问题第一个要回答的是「这个部署到底开了什么」。
     *
     * <p>只在真的挡掉东西时打 INFO。默认档下每次启动都刷一行「挡掉 0 个」，真正的告警会淹没在
     * 同一句话里。
     *
     * <p>{@code unused} 用 WARN 单独打：它既可能是「这个部署没装那个模块」（正常），也可能是
     * 「配置里拼错了」（不正常）。两者长得一样，所以列出来让人自己看，但不因此启动失败——裁剪
     * 部署时针对某个模块的 deny 条目本来就该落空。
     *
     * @param profile 生效的工具策略
     * @param selection 本次筛选的结果
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
     * 这次筛选的结论指纹：留下了哪些、被配置提到却不存在的是哪些。
     *
     * <p>用它去重是因为清单每轮解析（票 03）：内容一模一样时不该再打一遍日志，而内容变了
     * （MCP 恢复带进来一批工具、某个模块被裁掉）恰恰是要留一行的那件事。挡掉的原因不进指纹：
     * 同一批名字被挡掉，原因不会在两次解析之间变。
     *
     * @param selection 本次筛选的结果
     * @return 结论的指纹
     */
    private static String signatureOf(ToolProfile.Selection selection) {
        return String.join(",", selection.kept()) + "|"
                + String.join(",", selection.unused().stream().sorted().toList());
    }


    /**
     * 鉴权 Advisor：容器里所有 {@link ToolPermissionRegistry} 都由它消费，也就是「工具需要什么
     * 权限」由提供工具的模块自己声明，Agent 不认识任何具体工具。一个都没声明时它照样在链上，
     * 只是每个工具都因「未登记权限」被拒——失败的姿势是拒绝而不是放行（ADR-0007）。
     *
     * <p>审批门走 {@code ObjectProvider}（P01、P02）：它跟着 run 存储一起装配（审批表与 run 在
     * 同一个 schema 下），没有数据库的部署里它是缺席的。缺席时用 {@link ApprovalGate#denyAll()}，
     * 标了要审批的动作照样被拦，而不是因为少一个 bean 悄悄变成普通动作。
     *
     * <p>审计同理（P07）：它也跟 run 存储装配。缺席时判定照常，只是事后查不到这一次——被拒与
     * 等审批那两行没有落点。
     *
     * @param registries 各来源的工具权限登记表
     * @param gates 审批门，缺席时按全拒处理
     * @param meterRegistry 指标注册表，可能不存在
     * @param audit 审计接缝，可能不存在
     * @return 鉴权 Advisor
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
     * 出站脱敏：order 排在工具循环外面（见 {@link PiiRedactionAdvisor#ORDER}），整通对话只在出站前
     * 做一次。它不认识任何具体通道，也不改调用方，只是多加一个 bean。
     *
     * @return 脱敏 Advisor
     */
    @Bean
    @ConditionalOnMissingBean(PiiRedactionAdvisor.class)
    public PiiRedactionAdvisor piiRedactionAdvisor() {
        return new PiiRedactionAdvisor();
    }

    /**
     * 请求与响应的整份 dump，默认什么都不打：框架实现里每个方法体都包在
     * {@code logger.isDebugEnabled()} 里（Spring AI {@code SimpleLoggerAdvisor}:94），INFO 级别下
     * 连字符串拼接都不发生。
     *
     * <p>order 用框架默认的 0（{@code SimpleLoggerAdvisor}:130），也就是排在工具循环内部、紧贴
     * 模型调用，看到的是每一轮真正发给模型的东西（含工具返回的行）与模型的原始回复。想改成
     * 「只看用户最终看到的那一份」，得排到脱敏外层（order 小于
     * {@link PiiRedactionAdvisor#ORDER}），代价是看不到工具往返。
     *
     * <p>打开它之前先想清楚 PII：它 dump 的是模型入口与出口的原文，而脱敏在它外层，所以日志里是
     * 未脱敏的手机号与原始数据行。这也意味着它必须留在 DEBUG：{@code logging.level.org.springframework.ai.chat.client.advisor}
     * 一旦在某个环境里被调到 DEBUG，会话明文就落在日志里了。
     *
     * @return 日志 Advisor
     */
    @Bean
    @ConditionalOnMissingBean(SimpleLoggerAdvisor.class)
    public SimpleLoggerAdvisor simpleLoggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }

    /**
     * Agent 对外的门，通道只认这个类型（ADR-0010）。注册在这里意味着任何模块只要拿到 AgentPort
     * 就能驱动 Agent，不需要知道 ChatClient 的存在。
     *
     * @param chatClient 对话客户端
     * @param compactor 会话压缩器，可能不存在
     * @param delivery 结果投递，可能不存在
     * @param tracer 埋点入口，没开观测时用 {@link SpanTracer#NONE}
     * @param pointers 会话指针，可能不存在
     * @param audit 审计接缝，可能不存在
     * @param goals 会话目标存储，可能不存在
     * @param properties agent 配置，取 lane 等待上限
     * @return 实现 {@link AgentPort} 与 {@link TaskLauncher} 的那个门
     */
    @Bean
    @ConditionalOnMissingBean(AgentPort.class)
    public AgentPort agentPort(ChatClient chatClient,
                               ObjectProvider<SessionCompactor> compactor,
                               ObjectProvider<ConversationDelivery> delivery,
                               ObjectProvider<SpanTracer> tracer,
                               ObjectProvider<SessionEntries> pointers,
                               ObjectProvider<ToolCallAudit> audit,
                               ObjectProvider<io.github.claw4j.agent.goal.SessionGoalStore> goals,
                               AgentProperties properties) {
        // 投递走 ObjectProvider 而不是直接注入：没配推送实现时它是缺席而不是失败，
        // 门照常住着，只是结果只从请求里回去（ADR-0027：没有一个可推的地址就不推）。
        // 额度不在这条链上了——它是目标的属性（ADR-0027 决定五）。
        //
        // trace 也一样（P14）：没开观测时传空实现，埋点代码仍然逐字相同地跑一遍——
        // 那不是浪费，是让"开着"与"关着"走同一条路，否则关着的那条路平时不执行，
        // 等开了观测才第一次跑。
        // 段（ADR-0026）也走 ObjectProvider，但它与 run 那一组不同进同出：
        // 段回答"这是哪一段对话"，而"这件事做完了没有"没有对应的存储。
        //
        // lane 不是可选项：它是"一段对话同时只有一轮在跑"那条正确性保证本身。
        // 不装配它，并发两条消息就会各跑各的，而失败长相是"模型偶尔会忘事"。
        return new ChatClientAgentPort(chatClient, compactor.getIfAvailable(),
                delivery.getIfAvailable(),
                tracer.getIfAvailable(() -> SpanTracer.NONE), pointers.getIfAvailable(),
                new ConversationLane(properties.laneWait()), audit.getIfAvailable(),
                goals.getIfAvailable());
    }
}
