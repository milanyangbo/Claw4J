package io.github.claw4j.agent.toolaccess;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把英文的工具描述译成中文，只喂给向量索引（票 11，2026-11-26 按实测收窄范围）。
 *
 * <p>为什么要有它：框架自带的工具描述只有英文，而用户问的是中文。票 09 的基线量出这一档有多差：
 * 中文问英文描述，关键字那一路 R@1 0.20，纯向量 0.60。
 *
 * <p><b>为什么只给向量那一路</b>：同一份题、开着与关着各跑一遍，向量那一路受益
 * （hybrid-0.0 的 zh-en 档 0.60 → 0.80），关键字那一路反而降（regex 0.15 → 0.10）——
 * 描述变长，中文二元组可碰撞的字变多，无关工具更容易挤进前排。收益与代价分在两条路上，
 * 就不该让两条路读同一份文本（见 {@link TranslatedVectorIndex}）。
 *
 * <p><b>译成「中文 + 原文」而不是只留中文</b>：向量要的是语义，原文留着不碍事；而描述里常有
 * 参数名、路径语义这类译不准的东西，删掉原文等于把不确定性交给一次模型调用。
 *
 * <p><b>失败退原文，不抛</b>：中文化是增强，缺了只是命中率低一点；而启动失败会把整个服务挡在门外
 * （与 {@code claw4j.rag.enabled} 那条相反，理由不同：那边检索是功能本体）。
 *
 * <p><b>译文落表</b>（票 16）：进程内缓存留着，但它只在同一个进程里有意义。跨进程那一层是
 * {@code claw4j_tool.tool_description}（V9）：按 {@code 工具名 + 原文 hash} 命中，原文变了 hash
 * 就变，于是自动重译。
 *
 * <p>为什么非落表不可：票 13 的第三版量出中文化有收益，但**开的那一侧三次跑出三个 `R@1`**——
 * 每个进程重译一遍，措辞不同、embedding 不同、名次跟着变。一个自己会抖的指标不能当回归门。
 *
 * <p><b>表不可用就退回内存缓存</b>，只告警一次、不抛：中文化是增强（与 {@code claw4j.rag.enabled}
 * 那条相反，理由不同——那边检索是功能本体）。读失败与写失败分开告警：一个是"没省到钱"，
 * 一个是"下次还得译"，混成一条就分不出该修哪边。
 */
public final class ToolDescriptionTranslator {

    /**
     * 认它是"已经是中文"的门槛：描述里的汉字少于这么多，就当成没有中文。
     *
     * <p>6 这个数不是为了精确，是为了"一句话"的量级：{@code 读取文件内容} 是 6 个汉字，而
     * {@code Read a file} 里一个都没有。混合写法（中文一句 + 英文原文）远超 6，于是不会被再译一遍。
     */
    static final int MIN_CJK_CHARS = 6;

    /** 什么都不做的那个：没有配置、或开关关着时用它，调用方不必到处判空。 */
    public static final ToolDescriptionTranslator NONE =
            new ToolDescriptionTranslator(null, new ToolSearchProperties.ZhDescription(false, List.of()));

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ToolDescriptionTranslator.class);

    /** 读/写译文的表；{@code null} = 不落表（单元测试与不接库的部署）。 */
    private final JdbcTemplate jdbc;

    /** 一旦表读写失败就整轮退化：不再反复试（每次工具都试一次会把日志刷满、还拖慢首轮）。 */
    private boolean storageUsable = true;

    private final ChatModel model;

    private final ToolSearchProperties.ZhDescription config;

    /** toolName → 上一次译出来的东西。原文变了就重译，所以键里带 hash。 */
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** 已经为哪个工具告警过：同一个工具每轮告警一次会把日志刷满，而它是个外部依赖故障。 */
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();

    /**
     * 不落表的那一版：译文只活在进程内。
     *
     * @param model  用哪条链翻译；{@code null} 表示不做（{@link #NONE}）
     * @param config 开关与范围
     */
    public ToolDescriptionTranslator(ChatModel model, ToolSearchProperties.ZhDescription config) {
        this(model, config, null);
    }

    /**
     * @param model  用哪条链翻译；{@code null} 表示不做
     * @param config 开关与范围
     * @param jdbc   译文表；{@code null} = 只缓存在进程内
     */
    public ToolDescriptionTranslator(ChatModel model, ToolSearchProperties.ZhDescription config,
                                     JdbcTemplate jdbc) {
        this.model = model;
        this.config = config;
        this.jdbc = jdbc;
    }

    /**
     * 给一批工具引用换 summary，只用在向量索引的入库口。
     *
     * <p>并发译：一个工具一次调用、互不依赖，串行的话三十个工具就是几十秒的首轮。
     * 失败各自退原文，一个工具译不出来不影响其它。
     *
     * @param references 这一轮的工具
     * @return 换过 summary 的清单；与输入等长、同序。不需要译时**返回原列表**，不做无谓复制。
     */
    public List<ToolReference> translateReferences(List<ToolReference> references) {
        if (!this.config.enabled() || this.model == null || references.isEmpty()) {
            return references;
        }
        return references.parallelStream().map(this::translated).toList();
    }

    /**
     * 一个工具：能译就换，不能译就原样。
     *
     * <p>三种"不译"：开关关着（上面已挡）、描述里本来就有中文、名字不在
     * {@code zh-description-tools} 名单里（名单空 = 不限制）。
     */
    private ToolReference translated(ToolReference reference) {
        String name = reference.toolName();
        String description = reference.summary() == null ? "" : reference.summary();
        if (cjkCount(description) >= MIN_CJK_CHARS) {
            return reference;
        }
        if (!this.config.tools().isEmpty() && !this.config.tools().contains(name)) {
            return reference;
        }

        String hash = hash(description);
        Entry cached = this.cache.get(name);
        if (cached != null && cached.hash().equals(hash)) {
            return withChinese(reference, cached.chinese());
        }
        String stored = stored(name, hash);
        if (stored != null) {
            this.cache.put(name, new Entry(hash, stored));
            return withChinese(reference, stored);
        }
        try {
            String chinese = ask(name, description);
            this.cache.put(name, new Entry(hash, chinese));
            store(name, hash, description, chinese);
            return withChinese(reference, chinese);
        } catch (RuntimeException failure) {
            if (this.warned.putIfAbsent(name, Boolean.TRUE) == null) {
                // 只告警一次：这是外部依赖的问题（没 key、限流、超时），每轮刷一遍没有新信息。
                LOG.warn("工具 {} 的描述没译成中文，向量索引这一轮仍用原文：{}", name, failure.toString());
            }
            return reference;
        }
    }

    /**
     * 表里那一份译文；没有就 {@code null}。
     *
     * <p>失败一次就整轮不再读（{@code storageUsable = false}）：表都没了，后面每个工具再来一次
     * 只会让首轮多几十次异常。
     */
    private String stored(String name, String hash) {
        if (this.jdbc == null || !this.storageUsable) {
            return null;
        }
        try {
            List<String> rows = this.jdbc.queryForList(
                    "select zh_text from claw4j_tool.tool_description where tool_name = ? and source_hash = ?",
                    String.class, name, hash);
            return rows.isEmpty() ? null : rows.getFirst();
        }
        catch (DataAccessException unreadable) {
            this.storageUsable = false;
            LOG.warn("工具描述译文表读不出来，这一轮改用进程内缓存（重启才会再试）：{}", unreadable.toString());
            return null;
        }
    }

    /**
     * 把译文写回表。
     *
     * <p>按工具名覆盖那一行（{@code on conflict (tool_name) do update}），不按 hash 追加：
     * 一个工具只该有一份当前译文，留历史会让"这段描述当时译成什么"这句话有两种答案。
     */
    private void store(String name, String hash, String source, String chinese) {
        if (this.jdbc == null || !this.storageUsable) {
            return;
        }
        try {
            this.jdbc.update("insert into claw4j_tool.tool_description"
                            + " (tool_name, source_hash, source_text, zh_text) values (?, ?, ?, ?)"
                            + " on conflict (tool_name) do update set source_hash = excluded.source_hash,"
                            + " source_text = excluded.source_text, zh_text = excluded.zh_text,"
                            + " updated_at = now()",
                    name, hash, source, chinese);
        }
        catch (DataAccessException unwritable) {
            LOG.warn("工具 {} 的译文没存进表，下次还得重译：{}", name, unwritable.toString());
        }
    }

    /** 中文在前、原文在后：只改 summary，名字与相似度原样带走。 */
    private static ToolReference withChinese(ToolReference reference, String chinese) {
        return ToolReference.builder()
                .toolName(reference.toolName())
                .relevanceScore(reference.relevanceScore())
                .summary(chinese + "\n" + reference.summary())
                .build();
    }

    /**
     * 问一次模型。译文里一个汉字都没有时按失败处理——那说明它把原文抄回来了，留着反而让描述更长。
     *
     * <p>落表之后多了一步清理（2026-11-26 真机看到）：模型偶尔把提示词里的
     * 「工具名：X」「描述：Y」两行一起抄回来，译文变成
     * {@code 工具名：Bash\n描述：执行 bash 命令…}。以前只存在进程里，看不出来；
     * 现在它会被写进表、进向量索引，噪声就长期留在那儿了。
     */
    private String ask(String name, String description) {
        String answer = this.model.call(new Prompt("""
                把下面这句工具描述翻成一句简体中文。要求：保留工具名、参数名、代码标识符原样；
                不加解释；不换行；只输出译文。

                工具名：%s
                描述：%s""".formatted(name, description)))
                .getResult().getOutput().getText();
        String chinese = stripScaffolding(answer == null ? "" : answer, name);
        if (cjkCount(chinese) < MIN_CJK_CHARS) {
            throw new IllegalStateException("译出来的东西不像中文：" + chinese);
        }
        return chinese;
    }

    /**
     * 剥掉模型抄回来的提示词脚手架。
     *
     * <p>两种形态，都只在**开头**出现时才剥：
     * <ul>
     *   <li>{@code 工具名：Bash} 换行 {@code 描述：执行…} → 取「描述：」之后；</li>
     *   <li>{@code Bash：执行…} → 剥掉与工具名相同的前缀。</li>
     * </ul>
     *
     * <p>为什么只在开头：正文里出现「描述：」是正常的（"返回这段描述：…"），
     * 从中间截断会把一句好译文砍成半句。
     *
     * @param answer 模型的原始输出
     * @param name   工具名，用于剥第二种前缀
     * @return 清理后的译文；清理完是空就按失败处理（上层会退原文）
     */
    static String stripScaffolding(String answer, String name) {
        String text = answer.strip();
        if (text.startsWith("工具名：") || text.startsWith("工具名:")) {
            int at = text.indexOf("描述：");
            if (at < 0) {
                at = text.indexOf("描述:");
            }
            if (at >= 0) {
                text = text.substring(at + 3).strip();
            }
        }
        for (String colon : new String[] {"：", ":"}) {
            String prefix = name + colon;
            if (text.startsWith(prefix)) {
                text = text.substring(prefix.length()).strip();
                break;
            }
        }
        return text;
    }

    /** 汉字个数（CJK 统一表意文字；与中文二元组那套判据同一个范围）。 */
    static int cjkCount(String text) {
        return (int) text.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .count();
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 没有 SHA-256", impossible);
        }
    }

    /** 缓存里的一条：原文的 hash + 译出来的中文。 */
    private record Entry(String hash, String chinese) {
    }
}
