package io.github.claw4j.agent.toolaccess;

import io.github.claw4j.domain.ContextKeys;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.util.JsonHelper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工具循环：按配置决定这一轮**给模型看多少**，看不见的那部分靠 {@code toolSearchTool} 找回来。
 *
 * <p>为什么继承 {@link ToolCallingAdvisor} 而不是框架的 {@code ToolSearchToolCallingAdvisor}：
 * 后者每轮把清单硬替换成「检索工具 + 从 {@code MessageType.TOOL} 里认出的工具」，没有常驻字段，
 * 也不看工具数量；它的 {@code initializeSession} / {@code prepareIteration} 都是 {@code private}，
 * 在子类里加不进分支。而 {@code doInitializeLoop} / {@code doBeforeCall} 是 {@code protected}——
 * 循环本身（递归、预算、鉴权、流式）框架已经写好，这里只接管"这一轮给哪些工具"。
 *
 * <p>三档行为见 {@link ToolSearchProperties.Mode}。三档共用同一个类、同一条链：全塞只是
 * "不替换清单"这一个分支。分成两个 Advisor 的话，另一条路平时不执行，等于没人验。
 */
public class DisclosureToolCallingAdvisor extends ToolCallingAdvisor {

    /** 这一轮请求里的所有工具回调，按名字索引。与框架同名同用途，但各用各的（键前缀是本类）。 */
    private static final String CACHED_TOOL_CALLBACKS_KEY =
            DisclosureToolCallingAdvisor.class.getName() + ".cachedToolCallbacks";

    /** 这一轮是不是全塞。由 {@code doInitializeLoop} 定，{@code doBeforeCall} 每轮读。 */
    private static final String REVEAL_ALL_KEY = DisclosureToolCallingAdvisor.class.getName() + ".revealAll";

    private static final Logger LOGGER = LoggerFactory.getLogger(DisclosureToolCallingAdvisor.class);

    private static final JsonHelper JSON = new JsonHelper();

    private final ToolCallback toolSearchToolCallback;

    private final ToolIndex toolIndex;

    private final ToolSearchProperties properties;


    /** 接在系统提示词后面的那段话（隐藏模式用；全塞模式不拼，见 {@link #initialize}）。 */
    private final String systemMessageSuffix;

    /** 每个会话上一次建索引时的工具集指纹。工具集没变就不重建——重建向量索引要真花钱。 */
    private final Map<String, String> indexedFingerprints = new ConcurrentHashMap<>();

    /** 已经告警过的 {@code pinned} 名字。同一个名字每轮告警一次会把日志刷满，而它是个配置错误。 */
    private final Set<String> warnedPinnedNames = ConcurrentHashMap.newKeySet();

    /**
     * "用户这么说，就把这几个工具给他"——{@code rules} 里标了 {@code gate: true} 的那几条（票 34）。
     *
     * <p>与 {@code RuleToolIndex} 用的是同一份配置，但判据不同：那边看**模型改写的检索词**，
     * 这边看**用户原话**。要两个都留，是因为它们堵的是两个不同的洞：模型搜了、但那条规则的名字
     * 没被它写进 query，与模型压根不搜（用户点名它都断言"没有这个工具"）。
     */
    private final List<Mention> mentionRules;

    protected DisclosureToolCallingAdvisor(ToolCallingManager toolCallingManager, int advisorOrder,
                                           ToolExecutionEligibilityChecker toolExecutionEligibilityChecker,
                                           boolean conversationHistoryEnabled, ToolIndex toolIndex,
                                           ToolSearchProperties properties, String systemMessageSuffix) {
        super(toolCallingManager, toolExecutionEligibilityChecker, advisorOrder, conversationHistoryEnabled);
        this.toolIndex = Objects.requireNonNull(toolIndex, "toolIndex");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.systemMessageSuffix = systemMessageSuffix == null ? "" : systemMessageSuffix;
        this.mentionRules = compileMentions(properties.rules());
        this.toolSearchToolCallback = MethodToolCallbackProvider.builder()
                .toolObjects(new DescriptiveToolSearchTool(toolIndex, properties.maxResults()))
                .build()
                .getToolCallbacks()[0];
    }

    @Override
    public String getName() {
        return "DisclosureToolCallingAdvisor";
    }

    // ------------------------------------------------------------------ 同步

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest request, CallAdvisorChain chain) {
        if (request.prompt().getOptions() instanceof ToolCallingChatOptions options) {
            return initialize(request, options);
        }
        return super.doInitializeLoop(request, chain);
    }

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (request.prompt().getOptions() instanceof ToolCallingChatOptions) {
            return prepare(request);
        }
        return super.doBeforeCall(request, chain);
    }

    // ------------------------------------------------------------------ 流式

    @Override
    protected ChatClientRequest doInitializeLoopStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (request.prompt().getOptions() instanceof ToolCallingChatOptions options) {
            return initialize(request, options);
        }
        return super.doInitializeLoopStream(request, chain);
    }

    @Override
    protected ChatClientRequest doBeforeStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (request.prompt().getOptions() instanceof ToolCallingChatOptions) {
            return prepare(request);
        }
        return super.doBeforeStream(request, chain);
    }

    // ------------------------------------------------------------------ 共用

    /**
     * 开一轮：数工具、定"全塞还是检索"、检索模式下建索引并把提示词接上。
     *
     * <p>全塞时不建索引：那是纯开销，而这一轮一个检索都不会发生。也不拼提示词——提示词说的是
     * "你只看得见找工具这个工具"，全塞时这句话是假的，而模型会照它去做（先去检索一遍全量的东西）。
     *
     * @param request 请求
     * @param options 工具选项（调用方已判过类型）
     * @return 这一轮要用的请求
     */
    private ChatClientRequest initialize(ChatClientRequest request, ToolCallingChatOptions options) {
        String sessionId = sessionIdOf(request.context());
        List<ToolReference> references = referencesOf(options);
        boolean revealAll = this.properties.revealsAll(references.size());

        if (!revealAll) {
            indexIfChanged(sessionId, references);
        }

        Map<String, ToolCallback> callbacks = new ConcurrentHashMap<>();
        if (!CollectionUtils.isEmpty(options.getToolCallbacks())) {
            options.getToolCallbacks()
                    .forEach(callback -> callbacks.putIfAbsent(callback.getToolDefinition().name(), callback));
        }
        request.context().put(CACHED_TOOL_CALLBACKS_KEY, callbacks);
        request.context().put(DescriptiveToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY, sessionId);
        request.context().put(REVEAL_ALL_KEY, revealAll);

        if (revealAll) {
            return request;
        }
        // 2026-11-28 起不再往 system 末尾拼"这个目录有哪些工具"的清单：那是部署快照（66 个就四百字，
        // 接一批 MCP 就上千），而且把"存在"和"现在能调"混成一句——模型照着它点名一个没披露的工具，
        // 执行层只会抛异常。实测关掉之后，天气与区间成交额两句都在第一步自己发起了检索。
        String suffix = this.systemMessageSuffix;
        return request.mutate()
                .prompt(request.prompt()
                        .copy()
                        .augmentSystemMessage(message -> message.copy()
                                .mutate()
                                .text(message.getText() + suffix)
                                .build()))
                .build();
    }

    /**
     * 每一轮：决定这次交给模型的清单。
     *
     * @param request 请求
     * @return 清单可能被替换过的请求
     */
    @SuppressWarnings("unchecked")
    private ChatClientRequest prepare(ChatClientRequest request) {
        Object revealAll = request.context().get(REVEAL_ALL_KEY);
        if (Boolean.TRUE.equals(revealAll)) {
            return request;
        }
        ToolCallingChatOptions options = Objects.requireNonNull(
                (ToolCallingChatOptions) request.prompt().getOptions());

        Set<ToolCallback> selected = new LinkedHashSet<>();
        selected.add(this.toolSearchToolCallback);

        Map<String, ToolCallback> callbacks =
                (Map<String, ToolCallback>) request.context().get(CACHED_TOOL_CALLBACKS_KEY);
        if (callbacks != null) {
            addPinned(callbacks, selected);
            addInjected(callbacks, request.context().get(ContextKeys.INJECTED_TOOLS), selected);
            addMentioned(callbacks, selected, userTextOf(request.context()));
            referencedNames(request.prompt().getInstructions()).forEach(name -> {
                ToolCallback found = callbacks.get(name);
                if (found != null) {
                    selected.add(found);
                }
            });
        }

        ToolCallingChatOptions replaced = ((ToolCallingChatOptions.Builder<?>) options.mutate())
                .toolCallbacks(new ArrayList<>(selected))
                .toolContext(DescriptiveToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY,
                        Objects.requireNonNull(request.context().get(
                                DescriptiveToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY)))
                // 签名要按名字取完整定义，索引里只有名字与描述（票 32）。
                .toolContext(DescriptiveToolSearchTool.TOOL_CALLBACKS_KEY, callbacks)
                .build();
        return request.mutate()
                .prompt(request.prompt().mutate().chatOptions(replaced).build())
                .build();
    }

    /**
     * 常驻的那几个：配置说每轮都要在，那么不管检索到没检索到都在。
     *
     * <p>名字对不上时告警并跳过，不抛：同一份配置会在多个部署间共用（有人多接了几个 MCP，
     * 有人没接），写错一个名字不该让整个部署起不来。名字是否有效由这一行日志回答。
     *
     * @param callbacks 这一轮可用的回调
     * @param selected  要交给模型的清单
     */
    private void addPinned(Map<String, ToolCallback> callbacks, Set<ToolCallback> selected) {
        for (String name : this.properties.pinned()) {
            ToolCallback callback = callbacks.get(name);
            if (callback != null) {
                selected.add(callback);
            } else if (this.warnedPinnedNames.add(name)) {
                LOGGER.warn("claw4j.advisor.tool-search.pinned 里的 {} 不在这一轮的工具清单里，已忽略。"
                                + "可能拼错了，也可能它被工具策略（档位 / allow / deny）挡掉了。", name);
            }
        }
    }

    /**
     * 用户原话里提到的那几类事，把对应工具直接放进清单（票 34）。
     *
     * <p>为什么要这条：把通用工具移出常驻、改成"检索才给"之后，模型**一次都不搜**——2026-11-27 的
     * 两条探针里，用户直接点名"用 WebFetch 抓一下这个网址"，它答的是"我这边没有 WebFetch 这个工具"，
     * 检索次数 0。它一旦有"我上不了网"的先验，就不会去翻目录。所以需要一条不依赖它自觉的通路。
     *
     * <p>判据取**用户原话**而不是模型的检索词，是因为后者由模型改写，用户说"让我选"它可能写成
     * {@code deployment environment release pipeline}（票 32 现场）。用户说了什么，是这一层唯一
     * 稳定的输入。
     *
     * @param callbacks 这一轮可用的回调
     * @param selected  要交给模型的清单
     * @param text      本轮用户消息正文，可为 null
     */
    private void addMentioned(Map<String, ToolCallback> callbacks, Set<ToolCallback> selected,
                              @Nullable String text) {
        if (this.mentionRules.isEmpty() || text == null || text.isBlank()) {
            return;
        }
        for (Mention mention : this.mentionRules) {
            if (!mention.pattern().matcher(text).find()) {
                continue;
            }
            List<String> lit = new ArrayList<>(mention.tools().size());
            for (String name : mention.tools()) {
                ToolCallback found = callbacks.get(name);
                if (found != null && selected.add(found)) {
                    lit.add(name);
                }
            }
            if (!lit.isEmpty()) {
                LOGGER.debug("用户原话命中「{}」，这一轮直接给出：{}", mention.pattern().pattern(), lit);
            }
        }
    }

    /**
     * 编译"按原话点亮"的规则：只取 {@code gate: true} 的那几条。
     *
     * <p>没标 gate 的规则不动——它们本来就由 {@code RuleToolIndex} 在检索里置顶，把它们也塞进清单
     * 等于绕过检索，那是另一回事。正则写错在构造时抛，与 {@code RuleToolIndex} 同一个态度：
     * 别留到运行期被 catch 成一次空结果。
     *
     * @param rules 配置里的规则，可为 null
     * @return 编译好的规则
     */
    private static List<Mention> compileMentions(List<ToolSearchProperties.Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<Mention> mentions = new ArrayList<>();
        for (ToolSearchProperties.Rule rule : rules) {
            if (rule == null || !rule.gate() || rule.tools() == null || rule.tools().isEmpty()) {
                continue;
            }
            try {
                mentions.add(new Mention(Pattern.compile(rule.pattern()), List.copyOf(rule.tools())));
            } catch (PatternSyntaxException failure) {
                throw new IllegalArgumentException(
                        "claw4j.advisor.tool-search.rules 的正则写错了：" + rule.pattern(), failure);
            }
        }
        return List.copyOf(mentions);
    }

    /**
     * 本轮用户原话，从上下文取（{@link ContextKeys#USER_TEXT}）。
     *
     * <p><b>不从 {@code Prompt} 里取最后一条 user 消息</b>：知识库那层会把检索到的文档拼进用户消息
     * （Spring AI 问答顾问的形状），到这一步「最后一条 user 消息」里已经混着几段文档。实测过一次：
     * 用户只问"这个网页在讲什么"，按最后一条消息匹配却命中了 Bash 那条规则（文档里有"执行""测试"），
     * 于是每一轮都白给一个 Bash。
     *
     * @param context Advisor 上下文
     * @return 用户原话；调用方没写这个键时为 null（失败姿势是"什么都不点亮"）
     */
    private static @Nullable String userTextOf(Map<String, Object> context) {
        Object value = context == null ? null : context.get(ContextKeys.USER_TEXT);
        return value instanceof String text ? text : null;
    }

    /**
     * 一条"按原话点亮"的规则。
     *
     * @param pattern 用户原话里要出现的样子
     * @param tools   命中就给的工具名
     */
    private record Mention(Pattern pattern, List<String> tools) {
    }

    /**
     * 上游 advisor 注入的工具：每轮都要在，且不经过筛选（票 32）。
     *
     * <p>为什么这一批不能靠检索拿回来：注入方（长期记忆）在自己的系统提示里**点了名**要求模型
     * 先调 {@code MemoryView}。模型看得见名字、清单里没有，伸手就是
     * {@code IllegalStateException: No ToolCallback found}——不是降级，是整轮 500。
     *
     * <p>名字对不上时静默跳过：登记方在，回调不在，说明它被工具策略（档位 / allow / deny）挡掉了，
     * 那是策略该有的结果。这里告警的话，每个挡掉的场景都会刷一条，而挡掉是常态。
     *
     * @param callbacks 这一轮可用的回调
     * @param injected  上游登记的注入工具名
     * @param selected  要交给模型的清单
     */
    private void addInjected(Map<String, ToolCallback> callbacks, @Nullable Object injected,
                             Set<ToolCallback> selected) {
        if (!(injected instanceof Collection<?> names)) {
            return;
        }
        for (Object name : names) {
            ToolCallback callback = callbacks.get(String.valueOf(name));
            if (callback != null) {
                selected.add(callback);
            }
        }
    }

    /**
     * 把这一轮的工具建成索引——工具集没变就不重建。
     *
     * <p>指纹只算名字与描述：参数改了但名字描述没改时不该重建（模型的检索只看得见这两个字段）。
     * 用 SHA-256 而不是拼字符串，是因为名字里可能含有任何分隔符。
     *
     * @param sessionId  会话
     * @param references 这一轮的工具
     */
    private void indexIfChanged(String sessionId, List<ToolReference> references) {
        String fingerprint = fingerprintOf(references);
        this.indexedFingerprints.compute(sessionId, (id, current) -> {
            if (!fingerprint.equals(current)) {
                this.toolIndex.clearIndex(id);
                this.toolIndex.indexTools(id, references);
            }
            return fingerprint;
        });
    }

    private List<ToolReference> referencesOf(ToolCallingChatOptions options) {
        return this.toolCallingManager.resolveToolDefinitions(options).stream()
                .map(definition -> ToolReference.builder()
                        .toolName(definition.name())
                        .summary(definition.description())
                        .build())
                .toList();
    }

    private String sessionIdOf(Map<String, @Nullable Object> context) {
        String key = ChatMemory.CONVERSATION_ID;
        Assert.notNull(context, "context cannot be null");
        Assert.notNull(context.get(key), "context must contain a non-null value for '" + key + "'");
        return context.get(key).toString();
    }

    /**
     * 从历史里认出"之前检索到过的工具名"。
     *
     * <p>只认检索工具自己的响应：模型的正文里出现一个工具名不算数——那样用户随便说一句
     * "用 Read 读一下"就能把工具塞进清单，等于绕过检索。同一轮可能有多个并行检索，全都要认。
     *
     * @param messages 当前对话历史
     * @return 工具名，按出现顺序
     */
    private List<String> referencedNames(List<Message> messages) {
        String searchToolName = this.toolSearchToolCallback.getToolDefinition().name();
        return messages.stream()
                .filter(message -> message.getMessageType() == MessageType.TOOL)
                .map(message -> ((ToolResponseMessage) message).getResponses().stream()
                        .filter(response -> response.name().equalsIgnoreCase(searchToolName))
                        .toList())
                .filter(responses -> !responses.isEmpty())
                .flatMap(List::stream)
                .map(this::namesIn)
                .flatMap(List::stream)
                .toList();
    }

    /**
     * 从检索工具的响应里解出工具名。
     *
     * <p>两种形状都要认（票 32）：老的是 {@code ["Read","Grep"]}，新的是
     * {@code [{"name":"Read","description":"…","input":"…"}]}。只认一种的失效方式很坏——
     * 解析不出来只是"第二轮清单没更新"，模型搜到了工具却调不动，而日志上一个字都没有。
     * 所以这条既做兼容，也在"响应非空却一个名字都没解出来"时告警。
     *
     * @param response 检索工具的一条响应
     * @return 工具名，按出现顺序；解不出来时为空
     */
    private List<String> namesIn(ToolResponse response) {
        List<Object> hits = JSON.fromJson(response.responseData(),
                new ParameterizedTypeReference<List<Object>>() {
                });
        if (hits == null) {
            return List.of();
        }
        List<String> names = hits.stream()
                .map(DisclosureToolCallingAdvisor::nameOf)
                .filter(name -> !name.isBlank())
                .toList();
        if (names.isEmpty() && !hits.isEmpty()) {
            LOGGER.warn("检索工具返回了 {} 条命中，但一个工具名都没解出来，这一轮清单不会更新。"
                    + "响应开头：{}", hits.size(), abbreviate(response.responseData()));
        }
        return names;
    }

    /**
     * 一条命中的名字：老形状是字符串，新形状是带 {@code name} 的对象。
     *
     * @param hit 一条命中
     * @return 工具名；解不出来时为空串
     */
    private static String nameOf(Object hit) {
        if (hit instanceof String name) {
            return name;
        }
        if (hit instanceof Map<?, ?> map) {
            Object name = map.get("name");
            return name == null ? "" : String.valueOf(name);
        }
        return "";
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    private static String fingerprintOf(List<ToolReference> references) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            references.stream()
                    .sorted(Comparator.comparing(ToolReference::toolName))
                    .forEachOrdered(reference -> {
                        digest.update(reference.toolName().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        digest.update(reference.summary().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 1);
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 建这个循环的入口。
     *
     * @return 空的 builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 建这个循环。
     *
     * <p>不用"自引用泛型"（{@code Builder<T extends Builder<T>>}）：{@code builder()} 只能返回
     * {@code Builder<?>}，链式调用第二个方法时接收者就退化成父类，{@code properties(...)} 直接找不到。
     * 这里只有一个实现，不需要那种泛型。
     */
    public static class Builder extends ToolCallingAdvisor.Builder<Builder> {

        @Nullable private ToolIndex toolIndex;

        private ToolSearchProperties properties = ToolSearchProperties.defaults();

        private String systemMessageSuffix = "";

        protected Builder() {
        }

        /**
         * @param value 检索索引，必填
         * @return this
         */
        public Builder toolIndex(ToolIndex value) {
            Assert.notNull(value, "toolIndex cannot be null");
            this.toolIndex = value;
            return self();
        }

        /**
         * @param value 露出策略与检索参数
         * @return this
         */
        public Builder properties(ToolSearchProperties value) {
            Assert.notNull(value, "properties cannot be null");
            this.properties = value;
            return self();
        }

        /**
         * @param value 隐藏模式下接在系统提示词后面的话
         * @return this
         */
        public Builder systemMessageSuffix(String value) {
            this.systemMessageSuffix = value == null ? "" : value;
            return self();
        }

        @Override
        public DisclosureToolCallingAdvisor build() {
            Assert.notNull(this.toolIndex, "toolIndex is required");
            return new DisclosureToolCallingAdvisor(getToolCallingManager(), getAdvisorOrder(),
                    getToolExecutionEligibilityChecker(), isConversationHistoryEnabled(), this.toolIndex,
                    this.properties, this.systemMessageSuffix);
        }

        @Override
        protected ToolCallingAdvisor.Builder<?> newCopy() {
            return new Builder();
        }

        @Override
        public ToolCallingAdvisor.Builder<?> copy() {
            Builder copy = (Builder) super.copy();
            copy.toolIndex = this.toolIndex;
            copy.properties = this.properties;
            copy.systemMessageSuffix = this.systemMessageSuffix;
            return copy;
        }
    }
}
