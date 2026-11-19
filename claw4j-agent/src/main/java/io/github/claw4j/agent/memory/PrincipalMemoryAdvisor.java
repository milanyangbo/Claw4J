package io.github.claw4j.agent.memory;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
 * 是本票记下的缺口（见 {@code .scratch/m8-agent-quality/issues/A09-long-term-memory.md}）：
 * 要管得住，得先让请求上下文带上"本轮允许的工具名"，再改成求交集，那是改公开契约。
 */
public final class PrincipalMemoryAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PrincipalMemoryAdvisor.class);

    /**
     * 在链上的位置：persona 之后、脱敏与缓存之前。
     *
     * <p>必须大于 {@code AgentContextAdvisor.ORDER}（{@code +2}）：记忆块拼在最终系统提示的尾部，
     * persona 还没写进去时拼，会被它随后的改写盖掉。必须小于脱敏（{@code +200}）——虽然它没有
     * {@code after}，但"记忆在脱敏里面"这个相对顺序是这条链的既定分工。
     *
     * <p><b>不能用库的默认值</b>：那份默认是 {@code HIGHEST_PRECEDENCE + 200}，在本仓正好撞上
     * {@code PiiRedactionAdvisor.ORDER}。两个 advisor 同 order 时相对顺序由 bean 名字决定，
     * 而这种"顺序靠巧合"的链最难查。
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 3;

    /** 安全化之后的 userId 留多长；太长的 id 会把路径撑过文件系统的单段上限。 */
    private static final int MAX_SLUG = 48;

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
        return ORDER;
    }

    /**
     * 这个人的记忆目录。
     *
     * @param userId 通道侧的用户标识
     * @return 根目录下的一个子目录（不保证已存在，第一次写入时由库创建）
     */
    Path directoryOf(String userId) {
        return root.resolve(directoryName(userId));
    }

    private AutoMemoryToolsAdvisor advisorFor(Principal principal) {
        return advisors.computeIfAbsent(principal.userId(), userId -> {
            Path directory = directoryOf(userId);
            log.debug("长期记忆：用户 {} 的记忆目录是 {}", userId, directory);
            return AutoMemoryToolsAdvisor.builder()
                    // 外面这层才是链上的位置；库那份的 order 只在它自己进链时才用得到，
                    // 传同一个值是为了"从这里看过去只有一个位置"。
                    .order(ORDER)
                    .memoriesRootDirectory(directory.toString())
                    .build();
        });
    }

    /**
     * 把 {@code userId} 变成一个安全的目录名。
     *
     * <p>两件事都不能省：<b>安全化</b>——{@code userId} 来自通道（飞书 open_id、邮箱、工号都可能），
     * 里面有 {@code /} 或 {@code ..} 时 {@link Path#resolve} 会整个替换掉根目录，于是"这个人的记忆"
     * 落到别处；<b>哈希后缀</b>——安全化是多对一的（{@code a/b} 与 {@code a:b} 都变成 {@code a_b}），
     * 两个人撞进同一个目录就是互相读对方的记忆。
     *
     * <p>后缀取 SHA-256 的前 12 位十六进制（48 bit）：撞一条要试十亿量级的两两组合，而这里的
     * 全集是"这套部署里的用户数"。留成可读前缀是为了运维能一眼看出这是谁的目录——
     * 纯哈希的目录名在磁盘上等于匿名。
     *
     * @param userId 原始标识
     * @return 目录名，形如 {@code user_1-a1b2c3d4e5f6}
     */
    static String directoryName(String userId) {
        Objects.requireNonNull(userId, "userId");
        String slug = userId.replaceAll("[^A-Za-z0-9._-]", "_");
        if (slug.length() > MAX_SLUG) {
            slug = slug.substring(0, MAX_SLUG);
        }
        if (slug.isEmpty() || slug.equals(".") || slug.equals("..")) {
            slug = "user";
        }
        return slug + "-" + digest(userId);
    }

    private static String digest(String userId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(userId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        }
        catch (NoSuchAlgorithmException impossible) {
            // SHA-256 是 JDK 必须提供的算法；走到这里说明运行时不完整，不要降级成弱哈希。
            throw new IllegalStateException("这个 JVM 没有 SHA-256", impossible);
        }
    }
}
