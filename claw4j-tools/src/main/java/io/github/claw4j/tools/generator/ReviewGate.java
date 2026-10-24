package io.github.claw4j.tools.generator;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * 人审那一关：读审批标记，判断一个候选包能不能进工具清单。
 *
 * <p><b>这一步必须是人</b>，代码在这里只做两件事：读出人写下的决定，
 * 以及核对他审的是不是<b>这一版</b>脚本。没有"自动通过"的分支，
 * 也没有"审过一次就一直有效"的分支。
 *
 * <p>标记文件的三个字段各有各的用处：
 * <ul>
 *   <li>{@code status}：只有 {@code approved} 算通过，别的写法（空、{@code yes}、
 *       写错了的 {@code approve}）一律不算——"看着像通过"不算通过。
 *   <li>{@code by}：谁签的，空着不算。一条不知道是谁签的记录，事后回放时答不了"谁放的"。
 *   <li>{@code sha256}：他审的是哪一版。脚本被重新生成后摘要就变了，
 *       旧的那次签字自动作废——否则"审过一次"会变成一张长期通行证。
 * </ul>
 *
 * <p><b>标记文件不在暂存区里</b>，而且自测跑的是临时副本：
 * 把审批标记放在脚本能写到的地方，等于让未审核代码有机会给自己签字。
 * 这条不是 paranoid——沙箱能挡住的是"入口、参数、工作目录"，
 * 挡不住脚本体内去写一个绝对路径，所以只能让那个位置与脚本的执行范围不重叠。
 */
public final class ReviewGate {

    /** 通过时 {@code status} 必须写的就是这个字面量。 */
    public static final String APPROVED = "approved";

    /** 审批标记的后缀：{@code <review-directory>/<package>.properties}。 */
    public static final String SUFFIX = ".properties";

    private static final String STATUS = "status";

    private static final String BY = "by";

    private static final String AT = "at";

    private static final String SHA256 = "sha256";

    /**
     * 一次签字。
     *
     * @param by 谁签的
     * @param at 什么时候签的
     * @param sha256 他审的那版脚本的摘要
     */
    public record Decision(String by, Instant at, String sha256) {
    }

    /**
     * 读一个包的审批标记；没签、写得不合格都返回空。
     *
     * <p>写得不合格时返回空而不是抛异常：这一关的语义是"有没有人通过"，
     * 而"文件写坏了"与"没人签"在下游是同一件事——都不过。
     * 但坏在哪里要留在审计里，否则人看到的是"我签了却没生效"。
     */
    public Optional<Decision> read(Path reviewDirectory, String packageName) {
        Path file = fileOf(reviewDirectory, packageName);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        catch (IOException failure) {
            throw new IllegalStateException("读审批标记 " + file + " 失败", failure);
        }
        // 严格相等，不 strip：放行这件事上"差不多"不能算数——
        // 写成 "approved " 或 "Approved" 的文件，读的人会当成通过，而它答不出"谁审的哪版"。
        if (!APPROVED.equals(properties.getProperty(STATUS, ""))) {
            return Optional.empty();
        }
        String by = properties.getProperty(BY, "").strip();
        String sha = properties.getProperty(SHA256, "").strip();
        if (by.isEmpty() || sha.isEmpty()) {
            return Optional.empty();
        }
        Instant at;
        try {
            at = Instant.parse(properties.getProperty(AT, "").strip());
        }
        catch (RuntimeException failure) {
            return Optional.empty();
        }
        return Optional.of(new Decision(by, at, sha));
    }

    /** 这次签字对不对得上当前这一版脚本。 */
    public boolean matches(Decision decision, String script) {
        return decision.sha256().equalsIgnoreCase(digest(script));
    }

    /**
     * 写一份审批标记。
     *
     * <p>这个方法存在的意义是<b>给测试与人各留一条同样形状的路</b>：
     * 人可以用编辑器写这个文件，测试用这个方法写，读的是同一个格式，
     * 于是"测试通过"能说明"人这么写也生效"。
     */
    public Path approve(Path reviewDirectory, String packageName, String by, Instant at, String script)
            throws IOException {
        Path file = fileOf(reviewDirectory, packageName);
        Files.createDirectories(reviewDirectory);
        String content = String.join("\n",
                STATUS + "=" + APPROVED,
                BY + "=" + by,
                AT + "=" + at,
                SHA256 + "=" + digest(script),
                "");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    public static Path fileOf(Path reviewDirectory, String packageName) {
        return reviewDirectory.resolve(packageName + SUFFIX);
    }

    /** 脚本摘要：人审比对的是它，于是"审过之后脚本被改"会自动作废那次签字。 */
    public static String digest(String script) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(script.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    /** 给提示信息用：一次签字要写哪几项。 */
    public static List<String> requiredFields() {
        return List.of(STATUS, BY, AT, SHA256);
    }

    /** 装配断言与日志用：这一关在看哪个目录。 */
    public static Map<String, String> describe(Path reviewDirectory, String packageName) {
        return Map.of("file", fileOf(reviewDirectory, packageName).toString(),
                "fields", String.join(",", requiredFields()));
    }
}
