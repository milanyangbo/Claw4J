package io.github.claw4j.agent.approval;

import io.github.claw4j.capability.exec.ExecPolicy;
import io.github.claw4j.capability.exec.FrozenPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code allow-always} 的落点：{@code claw4j_run.exec_allowlist}（V11）上的一条持久放行（票 05）。
 *
 * <p>一条记录对<b>一段</b>命令，不是对整条命令行：批了 {@code rg -n TODO && ls} 会写成两行，
 * 于是下一次 {@code ls} 也在放行之内；而只落整条命令的哈希，下一次 {@code rg -n FIXME}
 * 还要再问一遍——人会开始无脑点批准，那比不问更糟。
 *
 * <p>"一段都表达不出来就整条不记"（{@link ExecPolicy#patternOf}）：{@code ./run.sh} 与
 * {@code /tmp/x} 这类取不出可执行名的段，落 basename 会让 {@code /tmp/evil/rg} 与 {@code rg}
 * 共用一条。宁可这次批准只生效一次，也不落一条过宽的。
 *
 * <p>写不进去只记日志：批准已经落了结论，这一条持久放行没写上是"下次还要再问一遍"，
 * 不是"这次没放行"。
 */
public class JdbcExecAllowlist {

    private static final Logger log = LoggerFactory.getLogger(JdbcExecAllowlist.class);

    /** 这一档的取值与 V11 的注释一致：目前只有 {@code allow-always}。 */
    static final String SOURCE_ALLOW_ALWAYS = "allow-always";

    /**
     * 每一段的 pattern 是不是都落过一条。
     *
     * <p>只认没有 {@code arg_pattern} 的条目：带了参数约束的条目（现在还没有）表达的是更窄的
     * 一件事，拿它放行整段等于把约束当不存在。
     */
    private static final String HAS_SQL =
            "select exists(select 1 from claw4j_run.exec_allowlist"
                    + " where pattern = ? and coalesce(arg_pattern, '') = '')";

    /** 同一个 pattern 只留一条：唯一索引兜住并发写，这里不先查后插。 */
    private static final String REMEMBER_SQL =
            "insert into claw4j_run.exec_allowlist (id, pattern, arg_pattern, source)"
                    + " values (?::uuid, ?, null, ?) on conflict do nothing";

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 主连接池，必填
     */
    public JdbcExecAllowlist(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 这条命令的每一段是不是都已经持久放行过。
     *
     * <p>读不出来按"没批过"处理：与审批账本同一个方向——出错时收紧才是安全的那一侧。
     *
     * @param segments 拆好的段
     * @return 每段都有条目时为 {@code true}
     */
    public boolean hasAll(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return false;
        }
        try {
            for (String segment : segments) {
                Optional<String> pattern = ExecPolicy.patternOf(segment);
                if (pattern.isEmpty()) {
                    // 这一段说不清是什么可执行文件，就不可能有一条针对它的放行。
                    return false;
                }
                Boolean present = jdbc.queryForObject(HAS_SQL, Boolean.class, pattern.get());
                if (!Boolean.TRUE.equals(present)) {
                    return false;
                }
            }
            return true;
        }
        catch (RuntimeException failure) {
            log.warn("allow-always 表读不出来：这条命令按“没批过”处理", failure);
            return false;
        }
    }

    /**
     * 这份计划能落成哪几条 pattern。有一段表达不出来就整份作废。
     *
     * <p>单独抽出来是因为同一个判断有两个消费者：写的时候决定落不落（{@link #remember}），
     * 画卡片的时候决定给不给那个按钮（{@link #remembers}）。两处各写一遍，迟早一处改成
     * "落一半"、另一处还在给按钮。
     *
     * @param plan 冻结件，可以为 {@code null}
     * @return 每段一条 pattern；有任何一段表达不出来、或一段都没有时为空
     */
    static Optional<Set<String>> patterns(FrozenPlan plan) {
        if (plan == null) {
            return Optional.empty();
        }
        Set<String> patterns = new LinkedHashSet<>();
        for (String segment : ExecPolicy.segments(plan.command())) {
            Optional<String> pattern = ExecPolicy.patternOf(segment);
            if (pattern.isEmpty()) {
                return Optional.empty();
            }
            patterns.add(pattern.get());
        }
        return patterns.isEmpty() ? Optional.empty() : Optional.of(patterns);
    }

    /**
     * 这份计划能不能落成持久放行。
     *
     * <p>落不了的时候卡片上不给 {@code allow-always}：那一项点了只会写零条、却回一句
     * "以后都放行"，下一次照旧弹——比没有这一项更坏。照 OpenClaw 的
     * {@code resolveExecApprovalAllowedDecisions}，它在"本次只能一次性持久化"时也只返回
     * {@code allow-once} 与 {@code deny}。
     *
     * @param plan 冻结件，可以为 {@code null}
     * @return 每段都能取到可执行名时为 {@code true}
     */
    static boolean remembers(FrozenPlan plan) {
        return patterns(plan).isPresent();
    }

    /**
     * 把这份计划按段记下来。
     *
     * @param plan 刚被批准的那份计划
     * @return 写下去的条目数；有一段表达不出来时为 0（整条不记）
     */
    public int remember(FrozenPlan plan) {
        Optional<Set<String>> resolved = patterns(plan);
        if (resolved.isEmpty()) {
            if (plan != null) {
                log.info("这条命令有一段表达不成可执行名，整条不落 allow-always：{}", plan.command());
            }
            return 0;
        }
        Set<String> patterns = resolved.get();
        int written = 0;
        for (String pattern : patterns) {
            try {
                written += jdbc.update(REMEMBER_SQL, UUID.randomUUID().toString(), pattern,
                        SOURCE_ALLOW_ALWAYS);
            }
            catch (RuntimeException failure) {
                // 一条写不进去不该连带其余几条：它们各自是独立的放行。
                log.warn("allow-always 条目没写进去：{}", pattern, failure);
            }
        }
        if (written > 0) {
            log.info("allow-always：{} 条命令段已持久放行（{}）", written, String.join("、", patterns));
        }
        return written;
    }
}
