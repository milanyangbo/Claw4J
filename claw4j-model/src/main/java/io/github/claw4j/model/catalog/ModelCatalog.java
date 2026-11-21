package io.github.claw4j.model.catalog;

import io.github.claw4j.model.config.ModelProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 目录：把配置里的每一行摊平成 {@code provider/model → 元数据}。
 *
 * <p>照 OpenClaw 的模型目录（`packages/model-catalog-core/src/model-catalog-types.ts`）。
 * 有了它，"上下文窗口是多少""这次请求能不能塞进这个模型"都能当面问出来，
 * 而不是回头去读会话配置里的那个数——那个数在降级之后就是错的。
 *
 * <p>{@code primary} 的两种写法：{@code provider/model} 指名某一行；
 * 只写 {@code provider} 表示"这一家的第一行"。后者是为了让只配一行的常见部署少写一遍模型名，
 * 而不是为了猜——目录里没有这个 provider 时照样启动失败。
 */
public final class ModelCatalog {

    private final Map<ModelRef, ModelRow> rows;

    private final ModelRef primary;

    private ModelCatalog(Map<ModelRef, ModelRow> rows, ModelRef primary) {
        this.rows = rows;
        this.primary = primary;
    }

    /**
     * 建目录。
     *
     * @param properties 供应商表（每家的 {@code models} 已按配置顺序）
     * @return 目录
     * @throws IllegalStateException 没有任何模型行，或 {@code primary} 在目录里找不到
     */
    public static ModelCatalog of(ModelProperties properties) {
        Map<ModelRef, ModelRow> rows = new LinkedHashMap<>();
        for (String providerId : properties.names()) {
            ModelProperties.Provider provider = properties.require(providerId);
            for (ModelRow row : provider.getModels()) {
                ModelRef ref = new ModelRef(providerId, row.getId());
                if (rows.put(ref, row) != null) {
                    throw new IllegalStateException("目录里有两个 " + ref
                            + "：同一家下面的模型 id 不能重复，否则引用到哪一行取决于绑定顺序。");
                }
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("claw4j.model.providers 里一个模型行都没有。"
                    + "每一家至少写一行 models: [{id: ...}]，否则没有可调用的模型。");
        }
        return new ModelCatalog(rows, resolvePrimary(properties.getPrimary(), rows));
    }

    /**
     * 主模型那一行的引用。
     *
     * @return 引用
     */
    public ModelRef primary() {
        return primary;
    }

    /**
     * 主模型那一行的元数据。
     *
     * @return 元数据
     */
    public ModelRow primaryRow() {
        return require(primary);
    }

    /**
     * 目录里所有行，按配置顺序（第一家在前、每家内部按 models 的顺序）。
     *
     * @return 不可变列表
     */
    public List<ModelRef> refs() {
        return List.copyOf(rows.keySet());
    }

    /**
     * 取一行的元数据，缺了就抛。报错里列出目录里有什么——这类错误的成因几乎都是名字写错。
     *
     * @param ref 引用
     * @return 元数据
     * @throws IllegalStateException 目录里没有这一行
     */
    public ModelRow require(ModelRef ref) {
        ModelRow row = ref == null ? null : rows.get(ref);
        if (row == null) {
            throw new IllegalStateException("模型目录里没有 " + ref + "，现在有 " + refs()
                    + "。检查 claw4j.model.providers.<id>.models[].id 与引用它的那一处。");
        }
        return row;
    }

    /**
     * 找一行，找不到返回空。
     *
     * @param ref 引用
     * @return 元数据
     */
    public Optional<ModelRow> find(ModelRef ref) {
        return Optional.ofNullable(ref == null ? null : rows.get(ref));
    }

    private static ModelRef resolvePrimary(String configured, Map<ModelRef, ModelRow> rows) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("claw4j.model.primary 未配置，不知道先调哪个模型。"
                    + "写法是 provider/model，目录里有 " + rows.keySet() + "。");
        }
        if (configured.contains("/")) {
            ModelRef ref = ModelRef.parse(configured);
            if (!rows.containsKey(ref)) {
                throw new IllegalStateException("claw4j.model.primary=" + configured
                        + " 不在目录里，现在有 " + rows.keySet() + "。");
            }
            return ref;
        }
        // 只写了供应商：取这一家的第一行。
        List<ModelRef> firstOfProvider = new ArrayList<>();
        for (ModelRef ref : rows.keySet()) {
            if (ref.provider().equals(configured)) {
                firstOfProvider.add(ref);
                break;
            }
        }
        if (firstOfProvider.isEmpty()) {
            throw new IllegalStateException("claw4j.model.primary=" + configured
                    + " 既不是目录里的 provider/model，也不是 providers 里的 id。"
                    + "现在有 " + rows.keySet() + "。");
        }
        return firstOfProvider.getFirst();
    }
}
