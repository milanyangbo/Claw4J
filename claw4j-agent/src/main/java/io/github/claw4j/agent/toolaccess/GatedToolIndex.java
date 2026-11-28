package io.github.claw4j.agent.toolaccess;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 通用工具的准入闸：配了 {@code gate} 的工具，问句点到了名就<b>提到候选最前面</b>。
 *
 * <p>它和 {@link RuleToolIndex} 是同一份配置的两个方向，所以放在一起看：
 * <ul>
 *   <li>{@link RuleToolIndex}：命中就提前（正向断言）；</li>
 *   <li>这一层：只对 {@code gate} 那几个工具判——命中提前，没命中留在原位（<b>不删</b>）。</li>
 * </ul>
 * 两件事必须用同一张表，否则会出现"规则把它排第一、闸又把它删了"这种自相矛盾的配置。
 *
 * <p><b>它不删任何候选</b>（2026-11-28 改）。原来这一层是"没命中就从候选里拿掉"，理由是
 * 票 28 / 29 的实测：{@code Bash} / {@code WebFetch} / {@code AskUserQuestionTool} / {@code Skill}
 * 这类工具对任何话题都能"凑合"，240 条真实链路里误调 56 条，这四个占 26 条。但"拿掉"这个动作
 * 的输入是一张手写的触发词表，而表是闭集：用户写了「用 bash 数一下 examples 下有多少个 .md 文件」，
 * 检索也确实召回了 {@code Bash}，只因为表里没有 {@code bash} 这个词，它就被砍掉，
 * 模型只好拿 {@code Glob} 反复试到上限。
 *
 * <p>检索能召回，就是检索对这句子给的结论；闸的职责是"调名次"，不是"替检索否决用户"。
 * 所以现在只做一件事：命中的排前面，没命中的跟在后面，两路按名字去重合并——
 * 截断时没命中的自然落到尾部，而不是凭空消失。
 *
 * <p><b>判据看模型的检索词</b>（{@link ToolSearchRequest#query()}），里面出现工具名本身也算命中：
 * 那是模型在说"我要这个工具"，比任何词表都准。
 *
 * <p>包在<b>最外层</b>（{@code Metered → Gated → Rule → 融合}）：它要看到置顶之后的最终顺序。
 */
public final class GatedToolIndex implements ToolIndex {

    private static final Logger log = LoggerFactory.getLogger(GatedToolIndex.class);

    private final ToolIndex delegate;

    /** 只留 {@code gate=true} 的那几条，编译一次。没配闸时整层不生效。 */
    private final List<Gate> gates;

    public GatedToolIndex(ToolIndex delegate, List<ToolSearchProperties.Rule> rules) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        List<Gate> compiled = new ArrayList<>();
        if (rules != null) {
            for (ToolSearchProperties.Rule rule : rules) {
                if (rule == null || !rule.gate()) {
                    continue;
                }
                if (rule.tools() == null || rule.tools().isEmpty()) {
                    throw new IllegalArgumentException(
                            "claw4j.advisor.tool-search.rules 的 " + rule.pattern() + " 标了 gate 却没写 tools");
                }
                try {
                    compiled.add(new Gate(Pattern.compile(rule.pattern()), Set.copyOf(rule.tools()),
                            rule.pattern()));
                }
                catch (PatternSyntaxException failure) {
                    // 与 RuleToolIndex 同一个态度：正则错在构造时抛，别留到检索时被 catch 成一次空结果。
                    throw new IllegalArgumentException(
                            "claw4j.advisor.tool-search.rules 的正则写错了：" + rule.pattern(), failure);
                }
            }
        }
        this.gates = List.copyOf(compiled);
    }

    /**
     * 一条闸都没有时原样返回：空转一层会让既有装配断言多包一层壳，收益是零。
     *
     * <p>与 {@link RuleToolIndex} 同一个写法——没配规则时不包壳，配了才包。
     */
    public static ToolIndex of(ToolIndex delegate, List<ToolSearchProperties.Rule> rules) {
        boolean anyGate = rules != null && rules.stream()
                .anyMatch(rule -> rule != null && rule.gate());
        return anyGate ? new GatedToolIndex(delegate, rules) : delegate;
    }

    /** 下一层。装配断言要剥开它看里面的顺序（闸必须在规则层外、融合层外）。 */
    public ToolIndex delegate() {
        return this.delegate;
    }

    @Override
    public void indexTool(String sessionId, ToolReference toolReference) {
        this.delegate.indexTool(sessionId, toolReference);
    }

    @Override
    public void indexTools(String sessionId, List<ToolReference> toolReferences) {
        this.delegate.indexTools(sessionId, toolReferences);
    }

    @Override
    public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {
        ToolSearchResponse plain = this.delegate.search(toolSearchRequest);
        if (this.gates.isEmpty() || plain == null || plain.toolReferences().isEmpty()) {
            return plain;
        }

        // 两路按名字去重合并：点到了名的闸工具（提前）+ 检索给的其余候选（保持原序）。
        // 一个都不删——被闸管着但没点名的只是排在后面，由 maxResults 自然截断。
        String query = toolSearchRequest.query();
        List<ToolReference> named = new ArrayList<>();
        List<ToolReference> rest = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ToolReference reference : plain.toolReferences()) {
            if (reference == null || reference.toolName() == null || !seen.add(reference.toolName())) {
                continue;
            }
            if (isGated(reference.toolName()) && namedIn(query, reference.toolName())) {
                named.add(reference);
            }
            else {
                rest.add(reference);
            }
        }
        if (named.isEmpty()) {
            return plain;
        }
        if (log.isDebugEnabled()) {
            log.debug("工具准入闸：query={} 提到前面={}", query,
                    named.stream().map(ToolReference::toolName).toList());
        }

        List<ToolReference> merged = new ArrayList<>(named.size() + rest.size());
        merged.addAll(named);
        merged.addAll(rest);
        return ToolSearchResponse.builder()
                .toolReferences(merged)
                .totalMatches(merged.size())
                .searchMetadata(plain.searchMetadata())
                .build();
    }

    @Override
    public void clearIndex(String sessionId) {
        this.delegate.clearIndex(sessionId);
    }

    /** 这个工具是不是被某条闸管着。 */
    private boolean isGated(String toolName) {
        for (Gate gate : this.gates) {
            if (gate.tools().contains(toolName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这句 query 点没点到这个工具的名：管它的闸里<b>任意一条</b>命中就算，或者 query 里直接写了工具名。
     *
     * <p>取"任意"不取"全部"：同一个工具被两条闸写着（比如一条管命令、一条管 git），
     * 用户说了其中一种就该给；取"全部"会让它只在两种话同时出现时才给。
     *
     * <p>写名字也算，是因为触发词表是闭集、永远漏得掉某一种写法；而"query 里出现了 bash"
     * 本身就是模型在说"我要这个工具"。
     *
     * @param query 模型给检索工具的那句 query，可为 null
     * @param toolName 工具名
     * @return 点到了就返回 true
     */
    private boolean namedIn(@Nullable String query, String toolName) {
        if (query == null) {
            return false;
        }
        if (containsName(query, toolName)) {
            return true;
        }
        for (Gate gate : this.gates) {
            if (gate.tools().contains(toolName) && gate.pattern().matcher(query).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这段文字里有没有直接写出这个工具名（忽略大小写）。
     *
     * <p>名字太短的（&lt;= 2 个字符）不参与判定：{@code sh}、{@code fs} 这种在英文句子里到处撞。
     *
     * @param text 待查的文字，可为 null
     * @param toolName 工具名
     * @return 出现了就返回 true
     */
    static boolean containsName(@Nullable String text, @Nullable String toolName) {
        if (text == null || toolName == null || toolName.length() <= 2) {
            return false;
        }
        return text.toLowerCase(java.util.Locale.ROOT).contains(toolName.toLowerCase(java.util.Locale.ROOT));
    }

    /** 一条闸：命中 {@code pattern} 就把 {@code tools} 提到前面。 */
    private record Gate(Pattern pattern, Set<String> tools, String source) {
    }
}
