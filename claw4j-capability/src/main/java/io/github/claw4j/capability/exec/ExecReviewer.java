package io.github.claw4j.capability.exec;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 未命中白名单时先问一句"这条命令低不低风险"的那一层（OpenClaw 的 native auto-reviewer）。
 *
 * <p>只有 {@code autoReview=true} 的档（{@code mode: auto}）才用它，且只在
 * {@code ask=on-miss} 且真的未命中时用。它的三个结局里**没有"拒绝"**——复核器说不行等于
 * "我判不了，交给人"，所以 {@link Decision#FALLBACK} 之后照常走审批，而不是直接拒。
 * 这条是它的原话：*Auto mode must never become a static allowlist*。
 *
 * <p><b>当前没有实现，默认实现恒 {@link Decision#FALLBACK}</b>，所以 {@code AUTO} 与 {@code ASK}
 * 行为完全一致（用户 2026-11-17 裁决：先不做，留扩展）。将来接模型只替换实现，不动枚举、配置和
 * 调用点。
 */
public interface ExecReviewer {

    /**
     * 看一眼这条命令。
     *
     * @param request 待复核的命令与上下文
     * @return 复核结论
     */
    Verdict review(Request request);

    /**
     * 复核器看到的全部内容。入参照 OpenClaw 给复核器的那份——它需要 argv 与解析后的路径是因为
     * "只看命令字符串"会被引号、别名、`env` 前缀绕过。
     *
     * @param command      原始命令
     * @param argv         拆好的参数（解析不出来时为空）
     * @param resolvedPath 解析后的可执行文件路径（解析不出来时为 {@code null}）
     * @param cwd          将在哪个目录里跑
     * @param envKeys      继承环境的**键名**（值不给：值里可能有密钥）
     * @param host         在哪跑
     * @param reason       为什么要复核（给人看的理由）
     * @param analysis     本地的静态分析结论，可以为空
     * @param agent        发起这次调用的 agent，可以为空
     */
    record Request(String command,
                   List<String> argv,
                   String resolvedPath,
                   Path cwd,
                   Set<String> envKeys,
                   String host,
                   String reason,
                   String analysis,
                   String agent) {

        public Request {
            argv = argv == null ? List.of() : List.copyOf(argv);
            envKeys = envKeys == null ? Set.of() : Set.copyOf(envKeys);
        }
    }

    /**
     * 复核结论。
     *
     * @param decision  放行一次，还是回落人工
     * @param risk      风险档（{@code low} / {@code medium} / {@code high} / {@code unknown}），只给人看
     * @param rationale 一句话理由，只给人看
     */
    record Verdict(Decision decision, String risk, String rationale) {

        public Verdict {
            risk = risk == null ? "unknown" : risk;
            rationale = rationale == null ? "" : rationale;
        }
    }

    /** 复核器的两个结局。没有"拒绝"：判不了就交给人。 */
    enum Decision {

        /** 低风险，放行这一次（不落库、不影响下一次）。 */
        ALLOW,

        /** 判不了，回落人工审批。 */
        FALLBACK
    }

    /**
     * 没有复核实现时的那一档：什么都不判，直接回落人工。
     *
     * @return 恒回落的复核器
     */
    static ExecReviewer fallback() {
        return request -> new Verdict(Decision.FALLBACK, "unknown", "没有复核实现，回落人工审批");
    }
}
