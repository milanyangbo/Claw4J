package io.github.claw4j.capability.generator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 生成链路的留痕：谁在什么时候走到哪一步、结果如何，落盘成一行一条的 JSONL。
 *
 * <p>它回答的是三个具体问题，不是一个笼统的"可观测"：
 * <b>这个包是谁生成的</b>（模型替谁写的）、<b>自测跑出来什么</b>（失败时那句原话）、
 * <b>它是什么时候被人放行的</b>（审计与注册之间隔了多久、中间有没有改过）。
 * 这三件事只在事后才会被问起，而事后能拿出来的只有当时写下的这一行。
 *
 * <p><b>只追加</b>：没有修改接口，也没有删除接口。一份能被改写的审计等于没有审计——
 * 它能回答的问题只剩"现在写着什么"。追加式的代价是文件会一直长，
 * 而这个量级（一次生成几行）上，长不是问题。
 *
 * <p>为什么不写进数据库：这条记录要能在"应用还没配数据库"时也成立
 * （脚本层与生成器都是可选层），而且它不该被业务表的权限体系管着——
 * 谁有权限看审计，是审计自己的事。
 */
public final class GenerationAudit {

    /** 链路上的每一步。回放时按这个顺序讲一件事。 */
    public enum Stage {
        /** 脚本被提交，通过了静态检查并落盘到暂存区。 */
        GENERATED,
        /** 静态检查挡下了：不落盘，只记下是谁提交的、命中了什么。 */
        STATIC_REJECTED,
        /** 沙箱自测跑完（成功或失败都记）。 */
        SELF_TESTED,
        /** 人签了字。 */
        APPROVED,
        /** 人审通过后进了工具清单。 */
        PUBLISHED,
        /** 已注册的包被真正执行了一次。 */
        EXECUTED
    }

    /**
     * 一条记录。
     *
     * @param at 发生时刻
     * @param principal 谁；取不到时是 {@code anonymous}——有一条不知道是谁的记录，
     *                  比没有记录更接近真相
     * @param stage 走到了哪一步
     * @param packageName 包名
     * @param detail 这一步说了什么（自测输出、命中的规则、拒绝原因）
     * @param allowed 这一步是放行还是拒绝
     */
    public record Entry(Instant at, String principal, Stage stage, String packageName, String detail,
                        boolean allowed) {

        static Entry parse(String line) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 6) {
                return null;
            }
            return new Entry(Instant.parse(fields[0]), unescape(fields[1]), Stage.valueOf(fields[2]),
                    unescape(fields[3]), unescape(fields[4]), Boolean.parseBoolean(fields[5]));
        }

        String serialize() {
            return String.join("\t",
                    at.toString(), escape(principal), stage.name(), escape(packageName), escape(detail),
                    String.valueOf(allowed));
        }
    }

    private static final String ANONYMOUS = "anonymous";

    private final Path file;

    public GenerationAudit(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * 追加一条。
     *
     * <p>写失败要抛出去：留痕失败却装作成功，比留痕本身失败更糟——
     * 后者能被发现（这里会炸），前者的表现是"审计里没有"，而没人会去看一个不存在的东西。
     */
    public synchronized void record(Instant at, String principal, Stage stage, String packageName,
                                    String detail, boolean allowed) {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(stage, "stage");
        String who = (principal == null || principal.isBlank()) ? ANONYMOUS : principal;
        String what = detail == null ? "" : detail;
        Entry entry = new Entry(at, who, stage, Objects.requireNonNull(packageName, "packageName"),
                truncate(what), allowed);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, entry.serialize() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException failure) {
            throw new UncheckedIOException("写审计记录失败：" + file, failure);
        }
    }

    /** 全部记录，按写入顺序。 */
    public synchronized List<Entry> all() {
        return read().stream().filter(Objects::nonNull).toList();
    }

    /** 只看一个包的记录：一条链路从生成到执行讲下来。 */
    public synchronized List<Entry> replay(String packageName) {
        return all().stream().filter(entry -> entry.packageName().equals(packageName)).toList();
    }

    /** 这个文件里有没有记录：装配断言用它区分"没启用"与"没人用过"。 */
    public synchronized int size() {
        return all().size();
    }

    private List<Entry> read() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                entries.add(Entry.parse(line));
            }
        }
        catch (IOException failure) {
            throw new UncheckedIOException("读审计记录失败：" + file, failure);
        }
        return entries;
    }

    /**
     * 详情要截断：脚本输出可以很大，而审计行是要被回看的——
     * 一行几兆的记录会让"翻一下审计"变成一次事故。
     */
    private static String truncate(String detail) {
        String flat = detail.replaceAll("\\s+", " ").strip();
        return flat.length() <= 500 ? flat : flat.substring(0, 500) + "…（截断）";
    }

    /**
     * 制表符分隔，所以正文里的换行、制表符与反斜杠都要转义。
     *
     * <p>手写的转义而不是引一个 JSON 库：这一个文件只有六个字段，
     * 而 tools 层为了一份日志去引一个解析依赖，是不划算的账。
     */
    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) {
                out.append(ch);
                continue;
            }
            char next = value.charAt(++i);
            out.append(switch (next) {
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                default -> next;
            });
        }
        return out.toString();
    }
}
