package io.github.claw4j.agent.toolaccess;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把英文的工具描述译成中文，一起交给模型与索引（票 11）。
 *
 * <p>为什么要有它：检索是词面匹配，而框架自带的描述只有英文。票 04 给网页那一族手写了几组同义说法
 * 搭桥，桥是逐个搭的、描述一改就静默失效。票 09 的基线量出这一档有多差：中文问英文描述，
 * 关键字那一路 R@1 0.20，纯向量 0.60。
 *
 * <p><b>译成「中文 + 原文」而不是只留中文</b>：描述里常有参数名、路径语义、边界条件，译丢一句就是
 * 模型误用一个工具。代价是每轮描述 token 约 1.6 倍，只对真译了的工具付。
 *
 * <p><b>包在回调上，不包在索引上</b>：索引是从回调的 {@link ToolDefinition} 建的，包在回调上
 * 两边自动同源；包在索引层的话，{@code mode=auto} 且工具少的部署是全塞（索引根本不建），
 * 模型看到的还是英文。
 *
 * <p><b>失败退原文，不抛</b>：中文化是增强，缺了只是命中率低一点；而启动失败会把整个服务挡在门外
 * （与 {@code claw4j.rag.enabled} 那条相反，理由不同：那边检索是功能本体）。
 *
 * <p><b>缓存只在进程内</b>：重启重译一遍（几十次小调用）。落表能省掉这一次，但那是一次 schema 变更，
 * 等它真的成为成本问题再说——见票 11 的缺口。
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

    private final ChatModel model;

    private final ToolSearchProperties.ZhDescription config;

    /** toolName → 上一次译出来的东西。原文变了就重译，所以键里带 hash。 */
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** 已经为哪个工具告警过：同一个工具每轮告警一次会把日志刷满，而它是个外部依赖故障。 */
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();

    /**
     * @param model  用哪条链翻译；{@code null} 表示不做（{@link #NONE}）
     * @param config 开关与范围
     */
    public ToolDescriptionTranslator(ChatModel model, ToolSearchProperties.ZhDescription config) {
        this.model = model;
        this.config = config;
    }

    /**
     * 给一份清单里的工具换描述。
     *
     * <p>并发译：一个工具一次调用、互不依赖，串行的话三十个工具就是几十秒的首轮。
     * 失败各自退原文，一个工具译不出来不影响其它。
     *
     * @param callbacks 这一轮筛完的工具
     * @return 换过描述的清单；与输入等长、同序
     */
    public List<ToolCallback> translate(List<ToolCallback> callbacks) {
        if (!this.config.enabled() || this.model == null) {
            return callbacks;
        }
        return callbacks.parallelStream().map(this::translated).toList();
    }

    /**
     * 一个工具：能译就换，不能译就原样。
     *
     * <p>四种"不译"：开关关着（上面已挡）、已经译过（子链拿到的清单里可能已经有）、
     * 描述里本来就有中文、名字不在 {@code zh-description-tools} 名单里（名单空 = 不限制）。
     */
    private ToolCallback translated(ToolCallback callback) {
        if (callback instanceof TranslatedToolCallback) {
            return callback;
        }
        ToolDefinition definition = callback.getToolDefinition();
        String name = definition.name();
        String description = definition.description() == null ? "" : definition.description();
        if (cjkCount(description) >= MIN_CJK_CHARS) {
            return callback;
        }
        if (!this.config.tools().isEmpty() && !this.config.tools().contains(name)) {
            return callback;
        }

        String hash = hash(description);
        Entry cached = this.cache.get(name);
        if (cached != null && cached.hash().equals(hash)) {
            return new TranslatedToolCallback(callback, cached.chinese());
        }
        try {
            String chinese = ask(definition, description);
            this.cache.put(name, new Entry(hash, chinese));
            return new TranslatedToolCallback(callback, chinese);
        } catch (RuntimeException failure) {
            if (this.warned.putIfAbsent(name, Boolean.TRUE) == null) {
                // 只告警一次：这是外部依赖的问题（没 key、限流、超时），每轮刷一遍没有新信息。
                org.slf4j.LoggerFactory.getLogger(ToolDescriptionTranslator.class)
                        .warn("工具 {} 的描述没译成中文，这一轮仍用原文：{}", name, failure.toString());
            }
            return callback;
        }
    }

    /**
     * 问一次模型。译文里一个汉字都没有时按失败处理——那说明它把原文抄回来了，留着反而让描述更长。
     */
    private String ask(ToolDefinition definition, String description) {
        String answer = this.model.call(new Prompt("""
                把下面这句工具描述翻成一句简体中文。要求：保留工具名、参数名、代码标识符原样；
                不加解释；不换行；只输出译文。

                工具名：%s
                描述：%s""".formatted(definition.name(), description)))
                .getResult().getOutput().getText();
        String chinese = answer == null ? "" : answer.strip();
        if (cjkCount(chinese) < MIN_CJK_CHARS) {
            throw new IllegalStateException("译出来的东西不像中文：" + chinese);
        }
        return chinese;
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
