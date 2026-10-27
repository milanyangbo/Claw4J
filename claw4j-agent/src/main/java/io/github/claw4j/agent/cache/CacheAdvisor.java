package io.github.claw4j.agent.cache;

import io.github.claw4j.agent.ToolCallLog;
import io.github.claw4j.agent.cache.SemanticCache.Candidate;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义缓存：同一个问题（或者换个说法问的同一个问题）第二次不再打模型、不再查库。
 *
 * <p><b>命中要同时满足四条</b>，少一条都可能把别人的答案或另一个问题的答案发出去：
 *
 * <ol>
 *   <li><b>同一个授权范围</b>。这条是安全边界，不是优化：{@code guest} 读不到
 *       {@code sales_order}、{@code admin} 读得到，同一个问题在两个角色下答案不同。
 *       缓存 key 里不带权限指纹，就是把 admin 的答案发给 guest。
 *       指纹取 {@code tables + actions} 排序后的哈希，**不取 userId**（按人分等于完全不共享），
 *       也**不取角色名**（角色改一次配置，缓存全失效）。
 *   <li><b>相似度过阈值</b>（{@link CacheProperties#DEFAULT_SIMILARITY_THRESHOLD}）。
 *   <li><b>时间词与数字完全一致</b>。这条是补丁，理由见 {@link #sameEntities}。
 *   <li><b>没过期</b>。过期判定在存储里（SQL 的 {@code expires_at > now()}）。
 * </ol>
 *
 * <p><b>带副作用的一轮不写缓存</b>：判断依据是这一轮**实际调用过的工具**的动作名，
 * 不是问题文本。规则取"所有动作都必须是 {@code read}"——{@code read} 是这个系统里
 * 唯一被声明为幂等的动作，{@code draft_script} / {@code run_script} / {@code recall_session}
 * 都不满足，于是自然被排除。没有调用任何工具的一轮（纯问答）照常缓存。
 *
 * <p><b>缓存的粒度是一轮问答</b>，不是工具结果：工具结果的窗口只有几分钟，
 * 而整轮问答的价值在于"同一个人十分钟后换个说法再问一遍"。
 *
 * <p><b>order 落在记忆之后、工具循环之前</b>（{@code DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50}）：
 * <ul>
 *   <li>比记忆大 → 记忆的 before 先把这条用户消息写进会话；命中时记忆的 after 照样会把
 *       "（同上次）"这条回答写回去，会话是连续的。
 *   <li>比工具循环小 → 整通对话只查一次，不会在每一轮工具迭代上重复查。
 *   <li>比脱敏大 → 命中时出站仍然过脱敏。所以存进去的是**未脱敏**的回答，
 *       和"没命中时"的回答走同一条出站路径，两边一致。
 * </ul>
 */
public class CacheAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(CacheAdvisor.class);

    /** 缓存命中次数。 */
    public static final String HITS = "claw4j.cache.hits";

    /** 未命中次数，带 {@code reason}。没有 reason 的话，"命中率低"查不出是哪一条在挡。 */
    public static final String MISSES = "claw4j.cache.misses";

    /** 命中时加在回答后面的标记。**它是这个功能唯一的用户可见证据**：没有它，用户分不清
     *  "答得快"是因为缓存还是因为模型那天心情好。 */
    public static final String MARKER = "（同上次）";

    /** 记忆之后、脱敏与工具循环之前。理由见类注释。 */
    public static final int ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50;

    static final String REASON_NO_CANDIDATE = "no-candidate";
    static final String REASON_BELOW_THRESHOLD = "below-threshold";
    static final String REASON_DIFFERENT_ENTITIES = "different-entities";
    static final String REASON_DIFFERENT_PERMISSION = "different-permission";
    static final String REASON_EMBEDDING_FAILED = "embedding-failed";

    /**
     * 中文里的相对时间词。**顺序有意义**：{@code 上上周} 必须排在 {@code 上周} 前面，
     * 否则 find 会先从第二个字开始匹配到"上周"，把"上上周"当成"上周"。
     */
    private static final Pattern TIME_WORDS = Pattern.compile(
            "上上周|上周|本周|这周|下周|上上个月|上个月|上月|本月|这个月|下个月"
                    + "|上季度|本季度|这季度|下季度|前年|去年|今年|明年"
                    + "|今天|昨天|前天|明天|后天|近\\s*\\d+\\s*(?:天|周|月)|最近\\s*\\d+\\s*(?:天|周|月)"
                    + "|\\d{4}\\s*年|\\d{1,2}\\s*月|\\d{1,2}\\s*[日号]");

    private static final Pattern NUMBERS = Pattern.compile("\\d+(?:\\.\\d+)?");

    private final SemanticCache cache;

    private final EmbeddingModel embeddings;

    private final CacheProperties properties;

    private final List<ToolPermissionRegistry> permissions;

    private final MeterRegistry registry;

    public CacheAdvisor(SemanticCache cache, EmbeddingModel embeddings, CacheProperties properties,
                        List<ToolPermissionRegistry> permissions, MeterRegistry registry) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.permissions = List.copyOf(permissions);
        this.registry = registry == null ? Metrics.globalRegistry : registry;
    }

    @Override
    public String getName() {
        return "cacheAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Objects.requireNonNull(request, "request");
        // 这一轮从这里开始。不清的话，线程池复用会让上一轮调用过的 draft_script
        // 把这一轮判成"有副作用"——表现是"有些本该缓存的没缓存"，不会报错。
        ToolCallLog.clear();

        Principal who = principalOf(request.context());
        String question = questionOf(request.prompt());
        if (who == null || question == null || question.isBlank()) {
            return chain.nextCall(request);
        }

        float[] embedding = embed(question);
        Optional<String> cached = answerFor(who, question, embedding);
        if (cached.isPresent()) {
            return hit(request.context(), cached.get());
        }

        ChatClientResponse response = chain.nextCall(request);
        remember(who, question, embedding, response);
        return response;
    }

    /**
     * 流式：**只读不写**。
     *
     * <p>读是安全的，而且对 SSE 与飞书的收益更大（用户对首字延迟最敏感）。
     * 写不做，因为流式下要拼出完整回答得靠累积每一块，而"每块是增量还是累计"
     * 因供应商而异——拼错就是缓存了一条重复十遍的回答，而它下次会被原样发出去。
     * 同步路径已经能把缓存填上（HTTP 与飞书都走它），不值得为这条路径冒那个险。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Objects.requireNonNull(request, "request");
        Principal who = principalOf(request.context());
        String question = questionOf(request.prompt());
        if (who == null || question == null || question.isBlank()) {
            return chain.nextStream(request);
        }
        Optional<ChatClientResponse> cached = answerFor(who, question, embed(question))
                .map(answer -> hit(request.context(), answer));
        return cached.map(Flux::just).orElseGet(() -> chain.nextStream(request));
    }

    /**
     * 找一条能用的缓存。四个判定都在这一个方法里，因为它们互相依赖——
     * "相似度过阈值但时间词不同"也是一种 miss，而且是**必须记下来**的那种：
     * 它是"语义相似度分不清上周和上上周"的证据。
     */
    private Optional<String> answerFor(Principal who, String question, float[] embedding) {
        if (embedding == null) {
            return miss(REASON_EMBEDDING_FAILED);
        }
        String fingerprint = fingerprintOf(who);
        Optional<Candidate> candidate = cache.nearest(fingerprint, question, embedding);
        if (candidate.isEmpty()) {
            // 本权限范围内没有候选。再看一眼"别的权限范围里有没有很像的"——两个用户问同一句话，
            // 一个命中一个 miss，日志上如果只写 no-candidate，会看起来像"缓存没生效"。
            boolean elsewhere = cache.nearestAnyFingerprint(embedding)
                    .filter(other -> other.similarity() >= properties.similarityThreshold())
                    .isPresent();
            return miss(elsewhere ? REASON_DIFFERENT_PERMISSION : REASON_NO_CANDIDATE);
        }
        Candidate found = candidate.get();
        if (found.similarity() < properties.similarityThreshold()) {
            log.debug("缓存候选相似度 {} 低于阈值 {}：{}", found.similarity(),
                    properties.similarityThreshold(), found.question());
            return miss(REASON_BELOW_THRESHOLD);
        }
        if (!sameEntities(question, found.question())) {
            log.debug("缓存候选相似度 {} 够高，但时间词或数字不同：{} vs {}",
                    found.similarity(), found.question(), question);
            return miss(REASON_DIFFERENT_ENTITIES);
        }
        Counter.builder(HITS).description("语义缓存命中次数").register(registry).increment();
        return Optional.of(found.answer());
    }

    /**
     * 时间词与数字必须完全一致。
     *
     * <p><b>为什么阈值救不了这件事</b>：实测（{@code text-embedding-v3}，1024 维）
     * "上周华东成交额多少" 与 "上上周华东成交额多少" 的余弦是 <b>0.9950</b>，
     * 而真正的同义改写 "华东上周卖了多少" 只有 0.9372。也就是说：只差一个字、
     * 意思却完全不同的两个问题，比换了说法的同一个问题还要"像"。
     * 任何单一阈值都无法同时做到"命中 0.9372"和"挡住 0.9950"。
     *
     * <p>所以加这一道词面守卫：两边的相对时间词集合、数字集合都相等才放行。
     * 它挡得住"上周/上上周"、"3 月/4 月"，挡不住的是同义词层面上的细微差别——
     * 这正是为什么要把它写成短小的正则而不是一个"语义实体抽取"框架：
     * 它的职责是<b>兜住已知的那一类错</b>，不是把所有错都兜住。
     */
    static boolean sameEntities(String left, String right) {
        return tokens(TIME_WORDS, left).equals(tokens(TIME_WORDS, right))
                && tokens(NUMBERS, left).equals(tokens(NUMBERS, right));
    }

    private static Set<String> tokens(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /**
     * 权限指纹：{@code tables} 与 {@code actions} 排序后拼一起再哈希。
     *
     * <p>三个"不取"各有代价：取 {@code userId} 等于同角色的人互相命中不了（缓存形同虚设）；
     * 取角色名则角色改一次配置、所有缓存失效（而且改配置看起来跟缓存无关）；
     * 取整个 {@link Principal} 的 {@code toString} 会因为字段顺序变化而漂移。
     */
    static String fingerprintOf(Principal who) {
        String tables = String.join(",", new TreeSet<>(who.accessibleTables()));
        String actions = String.join(",", new TreeSet<>(who.allowedActions()));
        return PgVectorSemanticCache.sha256(tables + "|" + actions);
    }

    /** 这一轮有没有副作用。空集合（一次工具都没调）算没有。 */
    private boolean sideEffectFree() {
        return sideEffectFree(ToolCallLog.current());
    }

    /**
     * 给定一组工具名，判断这一轮有没有副作用。
     *
     * <p>公开是为了让它能被单独测：这条规则一旦错，错的姿势是"把有副作用的一轮缓存了"，
     * 表现是"用户第二次问，拿到的是第一次的执行结果"——而它在正常使用里几乎看不出来。
     */
    public boolean sideEffectFree(Set<String> toolsCalled) {
        for (String tool : toolsCalled) {
            Optional<ToolPermission> permission = permissionFor(tool);
            // 没登记权限的工具按"有副作用"处理：没人评估过它会碰到什么，
            // 就不该把它的结果存下来给别人复用。
            if (permission.isEmpty() || !ToolPermission.READ.equals(permission.get().action())) {
                return false;
            }
        }
        return true;
    }

    private Optional<ToolPermission> permissionFor(String toolName) {
        for (ToolPermissionRegistry registry : permissions) {
            Optional<ToolPermission> permission = registry.permissionFor(toolName);
            if (permission.isPresent()) {
                return permission;
            }
        }
        return Optional.empty();
    }

    /** 没命中时不写缓存：embedding 算不出来、有副作用、回答为空，三种都不存。 */
    private void remember(Principal who, String question, float[] embedding, ChatClientResponse response) {
        if (embedding == null || !sideEffectFree()) {
            return;
        }
        String answer = textOf(response);
        if (answer == null || answer.isBlank()) {
            return;
        }
        try {
            // 末尾的脱敏还没跑，所以这里存的是原始回答——与"没命中时"的回答走同一条出站路径。
            cache.store(fingerprintOf(who), question, embedding, answer);
        }
        catch (RuntimeException failure) {
            // 缓存写不进去只是省不下成本，不能影响这一轮已经算出来的答案。
            log.warn("语义缓存写入失败，这一轮不缓存：{}", failure.getMessage());
        }
    }

    private Optional<String> miss(String reason) {
        Counter.builder(MISSES).description("语义缓存未命中次数")
                .tag("reason", reason)
                .register(registry)
                .increment();
        return Optional.empty();
    }

    private ChatClientResponse hit(Map<String, Object> context, String answer) {
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(answer + MARKER))))
                .build();
        return new ChatClientResponse(chatResponse, context);
    }

    /**
     * 算向量。**失败当成 miss**，不抛：embedding 服务挂了就该退化成"没有缓存"，
     * 而不是让整条对话答不了话——缓存是成本优化，不是可用性依赖。
     */
    private float[] embed(String question) {
        try {
            return embeddings.embed(question);
        }
        catch (RuntimeException failure) {
            log.warn("问题向量算不出来，这一轮当作没命中：{}", failure.getMessage());
            return null;
        }
    }

    /** 提示词里最后一条用户消息。历史里还有更早的 USER，取最后一个。 */
    private static String questionOf(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) {
            return null;
        }
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                return user.getText();
            }
        }
        return null;
    }

    private static String textOf(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null) {
            return null;
        }
        return response.chatResponse().getResult().getOutput().getText();
    }

    private static Principal principalOf(Map<String, Object> context) {
        Object value = context == null ? null : context.get(ContextKeys.PRINCIPAL);
        return value instanceof Principal principal ? principal : null;
    }
}
