package io.github.claw4j.domain.principal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 策略管线：把若干 {@link ToolPolicyLayer} 依次作用在清单上，**每层只减不增**（ADR-0030 裁决二）。
 *
 * <p>为什么是"逐层收窄"而不是"最后算一次合并策略"：合并会把"哪一层挡掉了它"这件事丢掉，而
 * 排查"我明明放开了，它怎么还在"时，第一个要回答的正是这个。所以每一层都留一条
 * {@link Step}，且**只在真的删了工具时才有条目**——没删的层不产出记录，否则一份十层的管线
 * 每次都刷十条空记录，真正有内容的那一条会被淹掉。
 *
 * <p>单层语义见 {@link ToolPolicyLayer}（deny 先判、allow 空则全放、非空则封闭其余）。
 *
 * <p>它是纯函数，不依赖 Spring、不读配置、不认工具名——组名展开（{@code group:*}）与
 * 画像判定都在**构造层**的地方做，因为那两件事需要清单与登记表，而管线只需要"一层怎么算"。
 *
 * <p>顺序有意义：同一批层换个顺序可能得到不同的结果（`allow` 非空的那层放前面会先封掉一批，
 * 放后面则先被 deny 掉一批）。调用方负责按 ADR 那张表排好，这里不排序、不去重。
 */
public record ToolPolicyPipeline(List<ToolPolicyLayer> layers) {

    public ToolPolicyPipeline {
        layers = layers == null ? List.of() : List.copyOf(layers);
    }

    /** 一条什么都不做的管线：清单原样返回。 */
    public static ToolPolicyPipeline none() {
        return new ToolPolicyPipeline(List.of());
    }

    /**
     * 逐层作用。
     *
     * @param tools 初始清单，保持顺序；重复项原样保留（重名是配置错误，该在别处红，不在这里合并）
     * @return 留下的清单，以及每一层删掉了什么
     */
    public Outcome apply(List<String> tools) {
        Objects.requireNonNull(tools, "tools");
        List<String> kept = new ArrayList<>(tools);
        List<Step> steps = new ArrayList<>();

        for (ToolPolicyLayer layer : layers) {
            if (layer.isEmpty()) {
                // 没配的层不算"执行过"：给它留一条空记录会让审计里出现一堆不相干的行。
                continue;
            }
            List<String> denied = new ArrayList<>();
            List<String> notAllowed = new ArrayList<>();
            List<String> survivors = new ArrayList<>();
            for (String name : kept) {
                String reason = layer.reasonFor(name);
                if (reason == null) {
                    survivors.add(name);
                } else if ("deny".equals(reason)) {
                    denied.add(name);
                } else {
                    notAllowed.add(name);
                }
            }
            if (!denied.isEmpty() || !notAllowed.isEmpty()) {
                steps.add(new Step(layer.label(), List.copyOf(denied), List.copyOf(notAllowed)));
                kept = survivors;
            }
        }
        return new Outcome(List.copyOf(kept), List.copyOf(steps));
    }

    /**
     * 一次管线作用的结果。
     *
     * @param kept  留下的工具名，保持输入顺序
     * @param steps 真的删掉了东西的那几层，按作用顺序
     */
    public record Outcome(List<String> kept, List<Step> steps) {

        /** 这次有没有真的挡掉东西。没有时启动日志少打一行，免得天天刷。 */
        public boolean droppedAnything() {
            return !steps.isEmpty();
        }

        /** 被任意一层挡掉的全部工具名，按首次出现的顺序去重。 */
        public List<String> dropped() {
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (Step step : steps) {
                step.removed().forEach(name -> seen.putIfAbsent(name, Boolean.TRUE));
            }
            return List.copyOf(seen.keySet());
        }
    }

    /**
     * 某一层干了什么。
     *
     * <p>两个桶分开记而不是一个 {@code removed}：{@code deny} 与"不在 allow 里"是两条相反的
     * 配置意图，日志里混成一句时，看到"挡掉 3 个"的人分不清是该去删 deny 还是该去补 allow。
     *
     * @param label      层标签，与配置键同形（{@code global} / {@code sender:id:alice}）
     * @param denied     因为命中 deny 被排除的
     * @param notAllowed 因为 allow 非空且没命中被排除的
     */
    public record Step(String label, List<String> denied, List<String> notAllowed) {

        /** 这一层挡掉的总数。 */
        public int count() {
            return denied.size() + notAllowed.size();
        }

        /** 这一层挡掉的全部工具名，deny 在前。 */
        public List<String> removed() {
            List<String> all = new ArrayList<>(denied);
            all.addAll(notAllowed);
            return List.copyOf(all);
        }
    }
}
