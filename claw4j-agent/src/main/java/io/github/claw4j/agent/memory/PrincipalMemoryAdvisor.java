package io.github.claw4j.agent.memory;

import io.github.claw4j.agent.advisor.AdvisorOrder;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 长期记忆：把库的 {@link AutoMemoryToolsAdvisor} 按人分发，并把记忆索引**直接注入**提示词。
 *
 * <p>库那一份把"记什么、什么时候记"全交给模型，工具（{@code MemoryView} / {@code MemoryCreate} /
 * …）与那段记忆系统提示都是它的；它缺的只有一件事——<b>记忆根目录是构建期一个固定字符串</b>，
 * 而本仓的每一次请求都带着身份（{@link ContextKeys#PRINCIPAL}），多用户共用一个目录的后果是
 * 甲的偏好出现在乙的提示词里。所以这里按 {@code userId} 取一份为那个人建好的 advisor 实例。
 *
 * <p><b>为什么不自己写那 6 个工具</b>：{@code AGENTS.md}「先找现成的」。库的工具带路径逃逸校验
 * （{@code resolveSafePath} 里 {@code normalize} + {@code startsWith}）、两段式写入提示
 * （建文件 + 往索引里加一行）、按 {@code type} 分类的 front matter 约定；重写一遍只是把这些
 * 再踩一遍，而且会漂。
 *
 * <p><b>但"读索引"不该是一次工具调用</b>（票 32）。库的设计是让模型先调 {@code MemoryView} 读
 * {@code MEMORY.md}，于是每个会话的第一个动作都是这次调用——白花一轮往返，还多一个失败点。
 * OpenClaw 的做法相反：{@code MEMORY.md} 在会话开始时<b>直接注入</b>，只有"可能用不上的"
 * 历史日志才靠检索（{@code docs.openclaw.ai/zh-CN/concepts/memory}）。这里照它：
 *
 * <ul>
 *   <li>用 {@code AutoMemoryToolsAdvisor.Builder#memorySystemPrompt(Resource)} 换掉提示词，
 *       改成"索引在下面、写用这几个工具"，不再要求先调 {@code MemoryView}；</li>
 *   <li>{@code before()} 转发之后，把 {@code MEMORY.md} 的内容追加到系统提示尾部；</li>
 *   <li>6 个工具仍然保留——{@code MEMORY.md} 之外的文件要靠它们翻，写也全靠它们。</li>
 * </ul>
 *
 * <p><b>截断</b>：索引超过 {@link #MAX_INDEX_CHARS} 字符就只注入前面一段，并在末尾注明"已截断，
 * 全文用 {@code MemoryView} 读"。与 OpenClaw 一致——磁盘上的文件永远是完整的，截的只是注入的那份；
 * 这也顺带说明为什么工具不能删。
 *
 * <p><b>注入的工具要登记</b>：披露层每轮整表重建清单，看不见"谁是上游刚递给模型的"。所以这里
 * 对比转发前后的工具名集合，把差集写进 {@link ContextKeys#INJECTED_TOOLS}——不硬编码名字，
 * 库升级换名也不会静默失效。消费方见 {@code DisclosureToolCallingAdvisor#prepare}。
 *
 * <p><b>没有身份就不注入</b>：不 fallback 到公共目录，也不 fallback 到"临时目录"。记忆是
 * 跨会话的持久状态，写错地方的代价比"这一轮没有记忆"大得多——失败姿势必须是"什么都没发生"。
 *
 * <p><b>它不在策略管线里</b>：注入发生在 prompt 层（库的机制就是这样），所以
 * {@code toolsBySender}、沙箱 {@code bundle-mcp}、画像过滤都管不到这 6 个工具。这不是疏忽，
 * 是本票记下的缺口（见 {@code .scratch/m8-agent-quality/issues/A13-long-term-memory.md}）：
 * 要管得住，得先让请求上下文带上"本轮允许的工具名"，再改成求交集，那是改公开契约。
 */
public final class PrincipalMemoryAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PrincipalMemoryAdvisor.class);

    /** 换掉库那份"先用 MemoryView 读索引"的说明书：索引已经注入，读的动作不再需要。 */
    private static final Resource SYSTEM_PROMPT =
            new ClassPathResource("prompt/MEMORY_SYSTEM_PROMPT.md");

    /**
     * 注入的索引上限（字符）。
     *
     * <p>取 6000 是量级判断不是实测：整理过的索引通常几十行，6000 字符足够放下；再多说明
     * 该把细节挪到其它文件、只在索引里留摘要。截断的代价只是"要多调一次 {@code MemoryView}"，
     * 比每轮把几万字塞进提示词小得多。
     */
    private static final int MAX_INDEX_CHARS = 6000;

    /** 索引文件名。库的约定，改这里等于改它的提示词。 */
    private static final String INDEX_FILE = "MEMORY.md";

    private static final String EMPTY_INDEX = "（还没有任何记忆，这是第一次对话。）";

    private final Path root;

    /**
     * 每人一份 advisor 实例。
     *
     * <p>缓存而不是每轮新建：库的实例持有 6 个已经装配好的 {@code ToolCallback}，每轮重建等于
     * 每轮重新反射一遍工具方法。键是原始 {@code userId}（不是目录名），于是"两个 id 安全化后同名"
     * 也知道它们是两个人。
     */
    private final Map<String, AutoMemoryToolsAdvisor> advisors = new ConcurrentHashMap<>();

    /**
     * @param root 记忆根目录，每个人在它下面一个子目录
     */
    public PrincipalMemoryAdvisor(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Object value = request.context() == null ? null : request.context().get(ContextKeys.PRINCIPAL);
        if (!(value instanceof Principal principal)) {
            return request;
        }
        Set<String> before = toolNamesOf(request);
        ChatClientRequest delegated = advisorFor(principal).before(request, chain);
        registerInjected(delegated, before);
        return withIndex(delegated, directoryOf(principal.userId()));
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        // 记忆的落盘由模型在那一轮里自己调工具完成（库的设计），收尾这一步没有可做的事。
        return response;
    }

    @Override
    public int getOrder() {
        return AdvisorOrder.PRINCIPAL_MEMORY;
    }

    /**
     * 这个人的记忆目录。
     *
     * @param userId 通道侧的用户标识
     * @return 根目录下的一个子目录（不保证已存在，第一次写入时由库创建）
     */
    Path directoryOf(String userId) {
        return MemoryDirectories.under(root, userId);
    }

    /**
     * 把这个人的 {@code MEMORY.md} 追加到系统提示尾部。
     *
     * <p>每轮读一次文件而不是缓存：索引很小（通常几 KB），一次本地读比"缓存失效策略写错导致
     * 模型看到上一版记忆"便宜得多。读失败按"没有记忆"处理并告警——记忆读不到不该让这一轮答不出来。
     *
     * <p><b>{@code augmentSystemMessage(String)} 是整段替换，不是追加</b>（Spring AI {@code Prompt}:252
     * 走的是 {@code systemMessage.mutate().text(newSystemText)}）。所以必须把现有正文自己拼回来——
     * 少拼这一下，人设和上面那段记忆说明会被整段覆盖掉，模型拿到的是一个只剩索引的系统提示
     * （票 33 复跑时被 {@code SessionMemoryTest} 钉住的，就是这一处）。
     *
     * @param request   已经过库那份处理过的请求
     * @param directory 这个人的记忆目录
     * @return 带上索引的请求
     */
    private ChatClientRequest withIndex(ChatClientRequest request, Path directory) {
        String existing = request.prompt().getSystemMessage() == null
                ? "" : request.prompt().getSystemMessage().getText();
        return request.mutate()
                .prompt(request.prompt().augmentSystemMessage(
                        existing + "\n\n## MEMORY.md（本次已加载）\n\n" + indexText(directory)))
                .build();
    }

    private String indexText(Path directory) {
        Path index = directory.resolve(INDEX_FILE);
        if (!Files.isRegularFile(index)) {
            return EMPTY_INDEX;
        }
        try {
            String text = Files.readString(index).strip();
            if (text.isEmpty()) {
                return EMPTY_INDEX;
            }
            if (text.length() <= MAX_INDEX_CHARS) {
                return text;
            }
            return text.substring(0, MAX_INDEX_CHARS)
                    + "\n\n（已截断：索引共 " + text.length() + " 字符，这里只放了前 "
                    + MAX_INDEX_CHARS + "。全文用 MemoryView 读 " + INDEX_FILE + "。）";
        } catch (IOException e) {
            log.warn("长期记忆：读不了 {}（{}），这一轮按没有记忆处理。", index, e.toString());
            return "（这一轮读不到记忆索引，需要时用 MemoryView 试一次。）";
        }
    }

    /**
     * 把这一轮注入的工具名登记进上下文，披露层每轮照着并回清单。
     *
     * <p>用"转发前后的差集"而不是硬编码名字：库将来改名、加工具、减工具，这里都不用跟着改；
     * 硬编码的失效方式是静默的——名字对不上就只是"某一轮工具又没了"，日志上什么都没有。
     *
     * @param request 库那份处理完的请求
     * @param before  转发之前这一轮已有的工具名
     */
    private void registerInjected(ChatClientRequest request, Set<String> before) {
        if (request.context() == null) {
            return;
        }
        Set<String> injected = toolNamesOf(request).stream()
                .filter(name -> !before.contains(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (injected.isEmpty()) {
            return;
        }
        if (request.context().get(ContextKeys.INJECTED_TOOLS) instanceof Collection<?> existing) {
            existing.forEach(name -> injected.add(String.valueOf(name)));
        }
        request.context().put(ContextKeys.INJECTED_TOOLS, List.copyOf(injected));
        log.debug("长期记忆：这一轮注入的工具 {}", injected);
    }

    private static Set<String> toolNamesOf(ChatClientRequest request) {
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options)
                || options.getToolCallbacks() == null) {
            return Set.of();
        }
        return options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private AutoMemoryToolsAdvisor advisorFor(Principal principal) {
        return advisors.computeIfAbsent(principal.userId(), userId -> {
            Path directory = directoryOf(userId);
            log.debug("长期记忆：用户 {} 的记忆目录是 {}", userId, directory);
            return AutoMemoryToolsAdvisor.builder()
                    // 外面这层才是链上的位置；库那份的 order 只在它自己进链时才用得到，
                    // 传同一个值是为了"从这里看过去只有一个位置"。
                    .order(AdvisorOrder.PRINCIPAL_MEMORY)
                    .memoriesRootDirectory(directory.toString())
                    .memorySystemPrompt(SYSTEM_PROMPT)
                    .build();
        });
    }
}
