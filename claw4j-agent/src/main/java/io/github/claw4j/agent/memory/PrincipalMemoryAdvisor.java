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

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期记忆：把库的 {@link AutoMemoryToolsAdvisor} 按人分发。
 *
 * <p>库那一份把"记什么、什么时候记"全交给模型，工具（{@code MemoryView} / {@code MemoryCreate} /
 * …）与那段记忆系统提示都是它的；它缺的只有一件事——<b>记忆根目录是构建期一个固定字符串</b>，
 * 而本仓的每一次请求都带着身份（{@link ContextKeys#PRINCIPAL}），多用户共用一个目录的后果是
 * 甲的偏好出现在乙的提示词里。所以这里只做分发：按 {@code userId} 取一份为那个人建好的
 * advisor 实例，其余原样转给它。
 *
 * <p><b>为什么不自己写那 6 个工具</b>：{@code AGENTS.md}「先找现成的」。库的工具带路径逃逸校验
 * （{@code resolveSafePath} 里 {@code normalize} + {@code startsWith}）、两段式写入提示
 * （建文件 + 往索引里加一行）、按 {@code type} 分类的 front matter 约定；重写一遍只是把这些
 * 再踩一遍，而且会漂。
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
        return advisorFor(principal).before(request, chain);
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

    private AutoMemoryToolsAdvisor advisorFor(Principal principal) {
        return advisors.computeIfAbsent(principal.userId(), userId -> {
            Path directory = directoryOf(userId);
            log.debug("长期记忆：用户 {} 的记忆目录是 {}", userId, directory);
            return AutoMemoryToolsAdvisor.builder()
                    // 外面这层才是链上的位置；库那份的 order 只在它自己进链时才用得到，
                    // 传同一个值是为了"从这里看过去只有一个位置"。
                    .order(AdvisorOrder.PRINCIPAL_MEMORY)
                    .memoriesRootDirectory(directory.toString())
                    .build();
        });
    }
}
