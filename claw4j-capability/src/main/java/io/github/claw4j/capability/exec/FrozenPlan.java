package io.github.claw4j.capability.exec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 一次审批冻结下来的执行计划：**批准的是这一份，跑的就是这一份**（ADR-0030 裁决四）。
 *
 * <p>为什么不是"执行前再算一遍参数指纹比对"：重算件与冻结件在正常路径上永远相等，于是它只在
 * 出错时才说话；而那时人已经点过批准了，能给的只有一句"变了"。存下批准那一刻的原文，才答得出
 * "批的是 {@code ./run.sh a}，现在要跑的是 {@code ./run.sh b}"——这句话是排查的全部入口。
 *
 * <p>只放**决定跑什么**的两样：命令与工作目录。会话与 Agent 不放，因为它们不改变这条进程的
 * 行为，只改变结论投回哪里——那件事记在审批行自己的 {@code session_key} 上。把不参与判定的字段
 * 塞进计划，比对时就要逐字段解释哪些"变了也不算变"，那正是这份计划想避免的模糊。
 *
 * <p>脚本类没有额外挂被调文件的 sha256：我们没有"脚本类审批"这个入口，脚本走 exec 时命令里
 * 只是一个路径，路径内容变了这一层看不出来。这条记在票 05 的缺口里。
 *
 * @param command 模型的原始命令（未规范化，逐个字符比对）
 * @param cwd     它将在哪个目录里跑
 */
public record FrozenPlan(String command, String cwd) {

    public FrozenPlan {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(cwd, "cwd");
    }

    /**
     * 从一次待批请求（或一次要跑的命令）冻结一份计划。
     *
     * @param command 整条命令
     * @param cwd     工作目录
     * @return 冻结件
     */
    public static FrozenPlan of(String command, Path cwd) {
        return new FrozenPlan(command, cwd == null ? "" : cwd.toString());
    }

    /**
     * 两份计划是不是同一次执行。
     *
     * <p>逐字符比，不做空白归一：改一个空格也是一条不同的命令（{@code rm -rf a b} 与
     * {@code rm -rf "a b"} 是两件事），而"看起来差不多"正是审批最不该替人判断的地方。
     *
     * @param other 另一份计划，可以为 {@code null}
     * @return 完全一致时为 {@code true}
     */
    public boolean sameAs(FrozenPlan other) {
        return other != null && command.equals(other.command) && cwd.equals(other.cwd);
    }

    /**
     * 这份计划在审批行上的键（{@code approval_request.args_digest}）。
     *
     * <p>与 {@code CallFingerprint} 分开：那一个是"工具名 + 规范化参数 JSON"，单位是一次工具调用；
     * 这一份的单位是"一条进程怎么起"。混用一个函数的话，哪天其中一边要加字段（比如脚本哈希），
     * 另一边会跟着变，而两边的批准不能互相替代。
     *
     * @return 64 位十六进制
     */
    public String digest() {
        return sha256(command + '\n' + cwd);
    }

    /**
     * 给人看的一句话：在哪个目录里跑什么。
     *
     * <p>审批卡片与拒绝话术都用它。分两行而不是拼成一行，是因为路径与命令都很长时，
     * 拼起来那句在卡片上会被截断，而截掉的往往正是命令本身。
     *
     * @return 摘要文本
     */
    public String summary() {
        return "目录：" + (cwd.isBlank() ? "（未指定）" : cwd) + "\n命令：" + command;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // 每个 JRE 都必须有 SHA-256；没有的话整条审批链都不该继续。
            throw new IllegalStateException("JRE 不支持 SHA-256", impossible);
        }
    }
}
