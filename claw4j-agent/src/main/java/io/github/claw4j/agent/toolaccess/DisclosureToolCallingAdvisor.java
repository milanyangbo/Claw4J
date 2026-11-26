package io.github.claw4j.agent.toolaccess;

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
import org.springframework.ai.tool.toolsearch.ToolSearchTool;
import org.springframework.ai.util.JsonHelper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private final ToolCategories categories;

    /** 接在系统提示词后面的那段话（隐藏模式用；全塞模式不拼，见 {@link #initialize}）。 */
    private final String systemMessageSuffix;

    /** 每个会话上一次建索引时的工具集指纹。工具集没变就不重建——重建向量索引要真花钱。 */
    private final Map<String, String> indexedFingerprints = new ConcurrentHashMap<>();

    /** 已经告警过的 {@code pinned} 名字。同一个名字每轮告警一次会把日志刷满，而它是个配置错误。 */
    private final Set<String> warnedPinnedNames = ConcurrentHashMap.newKeySet();

    protected DisclosureToolCallingAdvisor(ToolCallingManager toolCallingManager, int advisorOrder,
                                           ToolExecutionEligibilityChecker toolExecutionEligibilityChecker,
                                           boolean conversationHistoryEnabled, ToolIndex toolIndex,
                                           ToolSearchProperties properties, String systemMessageSuffix) {
        super(toolCallingManager, toolExecutionEligibilityChecker, advisorOrder, conversationHistoryEnabled);
        this.toolIndex = Objects.requireNonNull(toolIndex, "toolIndex");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.categories = ToolCategories.of(properties.categories());
        this.systemMessageSuffix = systemMessageSuffix == null ? "" : systemMessageSuffix;
        this.toolSearchToolCallback = MethodToolCallbackProvider.builder()
                .toolObjects(new ToolSearchTool(toolIndex, properties.maxResults()))
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
        request.context().put(ToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY, sessionId);
        request.context().put(REVEAL_ALL_KEY, revealAll);

        if (revealAll) {
            return request;
        }
        String suffix = this.systemMessageSuffix
                + this.categories.promptLine(references.stream().map(ToolReference::toolName).toList());
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
            referencedNames(request.prompt().getInstructions()).forEach(name -> {
                ToolCallback found = callbacks.get(name);
                if (found != null) {
                    selected.add(found);
                }
            });
        }

        ToolCallingChatOptions replaced = ((ToolCallingChatOptions.Builder<?>) options.mutate())
                .toolCallbacks(new ArrayList<>(selected))
                .toolContext(ToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY,
                        Objects.requireNonNull(request.context().get(
                                ToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY)))
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

    private List<String> namesIn(ToolResponse response) {
        List<String> names = JSON.fromJson(response.responseData(),
                new ParameterizedTypeReference<List<String>>() {
                });
        return names == null ? List.of() : names;
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
