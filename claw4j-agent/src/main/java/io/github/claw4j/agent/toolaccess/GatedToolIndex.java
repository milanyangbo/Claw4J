package io.github.claw4j.agent.toolaccess;

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
 * 通用工具的准入闸：配了 {@code gate} 的工具，问句没点名就不进候选。
 *
 * <p>它和 {@link RuleToolIndex} 是同一份配置的两个方向，所以放在一起看：
 * <ul>
 *   <li>{@link RuleToolIndex}：命中就把工具<b>提前</b>（正向断言）；</li>
 *   <li>这一层：没命中就把工具<b>拿掉</b>（反向断言）。</li>
 * </ul>
 * 两件事必须用同一张表，否则会出现"规则把它排第一、闸又把它删了"这种自相矛盾的配置，
 * 而且从现象上只看得出"检索时好时坏"。
 *
 * <p><b>为什么需要这一层</b>（票 28 / 29，ADR-0039）：{@code Bash} / {@code WebFetch} /
 * {@code AskUserQuestionTool} / {@code Skill} 这类工具对任何话题都能"凑合"——模型不是在执行，
 * 是在拿它们算个数、上个网、问一句。票 28 的 240 条真实链路里误调 56 条，这四个占 26 条。
 * 检索只会做"按像不像排队、永远凑够 5 个"，所以光靠打分压不住它们：必须有一层说得清
 * "这句话压根没让你动这台机器"的闸。
 *
 * <p><b>它不做意图判断</b>：凭的是配置里那几个触发词（闭集），不是"这句是不是在闲聊"。
 * 票 22 删掉 {@code ChitchatToolIndex} 就是因为后者要在检索层猜意图；这一层不猜——问句里
 * 有没有"跑/编译/网址"是能逐条对数的事实，判错了也能从配置改回来。
 *
 * <p><b>兜底优先于净化</b>：过滤后不足 {@value #MIN_CANDIDATES} 个候选时，按原来的名次把被拿掉的
 * 补回来。空候选比误调更难查——模型没得调时会自己编一个答案，或者干脆说"我没有这个能力"，
 * 两种都看不出是检索的问题（票 28 里那条空候选就是这么暴露的）。
 *
 * <p>包在<b>最外层</b>（{@code Metered → Gated → Rule → 融合}）：它要看到置顶之后的最终顺序，
 * 否则"规则点名了却被后面的截断挤掉"这件事它看不见。
 */
public final class GatedToolIndex implements ToolIndex {

    private static final Logger log = LoggerFactory.getLogger(GatedToolIndex.class);

    /**
     * 过滤后至少要剩这么多个候选，不够就补回被拿掉的。
     *
     * <p>取 3 而不是 5：留 5 就等于不闸（通用工具通常正好占满头几名）。3 是"还挑得动"与
     * "别只剩一个别无选择"的折中，也是票面临时定的数，没有更细的依据。
     */
    static final int MIN_CANDIDATES = 3;

    private final ToolIndex delegate;

    /** 只留 {@code gate=true} 的那几条，编译一次。没配闸时整层不生效。 */
    private final List<Gate> gates;

    /**
     * 已经告警过的工具名。写错名字在这里只是"这条闸永远不命中"，与"这条闸没配"分不开，
     * 所以要说一声——但不能每轮刷屏。
     */
    private final Set<String> warned = new LinkedHashSet<>();

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

        String query = toolSearchRequest.query();
        List<ToolReference> kept = new ArrayList<>();
        List<ToolReference> dropped = new ArrayList<>();
        for (ToolReference reference : plain.toolReferences()) {
            if (reference == null || reference.toolName() == null) {
                continue;
            }
            if (isGated(reference.toolName()) && !allowed(reference.toolName(), query)) {
                dropped.add(reference);
            }
            else {
                kept.add(reference);
            }
        }
        if (dropped.isEmpty()) {
            return plain;
        }

        List<ToolReference> chosen = kept.size() >= MIN_CANDIDATES
                ? kept : backfill(kept, dropped);
        if (log.isDebugEnabled()) {
            log.debug("工具准入闸：query={} 拿掉={} 最终={}", query,
                    dropped.stream().map(ToolReference::toolName).toList(),
                    chosen.stream().map(ToolReference::toolName).toList());
        }

        return ToolSearchResponse.builder()
                .toolReferences(chosen)
                .totalMatches(chosen.size())
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
     * 这个工具此刻放不放行：管它的那些闸里，<b>任意一条</b>命中就放行。
     *
     * <p>取"任意"不取"全部"：同一个工具被两条闸写着（比如一条管命令、一条管 git），
     * 用户说了其中一种就该给。取"全部"会让它只在两种话同时出现时才出现，等于永远不给。
     */
    private boolean allowed(String toolName, String query) {
        boolean gated = false;
        for (Gate gate : this.gates) {
            if (!gate.tools().contains(toolName)) {
                continue;
            }
            gated = true;
            if (query != null && gate.pattern().matcher(query).find()) {
                return true;
            }
        }
        if (gated && this.warned.add(toolName) && log.isDebugEnabled()) {
            // 只在 debug 说：这条闸没命中的常见原因是"用户确实没让它干活"，不是配置错。
            log.debug("工具 {} 被准入闸拿掉：问句里没有它的触发词", toolName);
        }
        return false;
    }

    /**
     * 按原来的名次把被拿掉的补回末尾，补到 {@link #MIN_CANDIDATES} 为止。
     *
     * <p>补在末尾而不是插回原位：留下的那几个仍然是"最像"的，通用工具只是"凑够数量"。
     */
    private List<ToolReference> backfill(List<ToolReference> kept, List<ToolReference> dropped) {
        List<ToolReference> merged = new ArrayList<>(kept);
        for (ToolReference reference : dropped) {
            if (merged.size() >= MIN_CANDIDATES) {
                break;
            }
            merged.add(reference);
        }
        return merged;
    }

    /** 一条闸：命中 {@code pattern} 就放行 {@code tools}。 */
    private record Gate(Pattern pattern, Set<String> tools, String source) {
    }
}
