package io.github.claw4j.app.doctor;

import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 库里的迁移跑到哪一版了。
 *
 * <p><b>不走 {@code Flyway#info()}</b>：{@code spring.flyway.enabled} 默认是 false，
 * 那种部署里根本没有 Flyway bean；而"库落后了"恰恰是要查的问题之一。所以这里自己读两处：
 * classpath 上的 {@code db/migration/*.sql}（各模块的迁移都在里面）与库里的
 * {@code flyway_schema_history}。
 *
 * <p>只比"最大值"够不够的差别，不比中间缺号：Flyway 自己会拦中间缺号，doctor 不重复实现它的校验。
 *
 * <p>连库失败与"表不存在"是两条不同的结果，修复动作也不同：前者去查 URL 与凭据，后者是把迁移跑一遍。
 * 合成一句"迁移检查失败"等于让人自己去猜。
 */
public final class MigrationCheck implements DoctorCheck {

    /** 迁移文件名：{@code V12__approval_frozen_plan.sql}。 */
    private static final Pattern FILE_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    /** 视图版本号只有整数；带点的版本按主号比（本仓没有），报错时列出原始字符串。 */
    private static final Pattern LEADING_DIGITS = Pattern.compile("^(\\d+)");

    private final Supplier<List<Integer>> classpathVersions;

    private final AppliedVersions appliedVersions;

    /** 生产用的那一份：classpath 扫资源，库里查历史表。 */
    public MigrationCheck() {
        this(MigrationCheck::readClasspathVersions, MigrationCheck::readAppliedVersions);
    }

    MigrationCheck(Supplier<List<Integer>> classpathVersions, AppliedVersions appliedVersions) {
        this.classpathVersions = classpathVersions;
        this.appliedVersions = appliedVersions;
    }

    @Override
    public String title() {
        return "数据库迁移";
    }

    @Override
    public List<Finding> run(Environment environment) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || url.isBlank()) {
            return List.of(Finding.skip(title(), "spring.datasource.url 没配，这一项查不了"));
        }
        List<Integer> expected = classpathVersions.get();
        if (expected.isEmpty()) {
            return List.of(Finding.skip(title(), "classpath 上没有 db/migration/*.sql，这个部署不带迁移"));
        }
        List<String> applied;
        try {
            applied = appliedVersions.read(environment);
        }
        catch (SQLException unreachable) {
            return List.of(Finding.fail(title(), "连不上库或读不到历史表：" + oneLine(unreachable.getMessage()),
                    missingTable(unreachable)
                            ? "库里还没有 flyway_schema_history：把迁移跑一遍（用 local profile 起一次，"
                                    + "或手工执行 db/migration 下的脚本）"
                            : "确认 CLAW4J_DB_URL / CLAW4J_DB_USER / CLAW4J_DB_PASSWORD，以及库是否在跑"));
        }
        return List.of(compare(expected, applied));
    }

    /**
     * 拿两边的版本对一下。
     *
     * @param expected classpath 上的版本
     * @param applied  库里 success = true 的版本（原始字符串，可能有带点的那种）
     * @return 一条结果
     */
    static Finding compare(List<Integer> expected, List<String> applied) {
        Set<Integer> known = new TreeSet<>();
        List<String> unparsed = new ArrayList<>();
        for (String raw : applied) {
            Integer version = leadingNumber(raw);
            if (version == null) {
                unparsed.add(raw);
            }
            else {
                known.add(version);
            }
        }
        Set<Integer> missing = new LinkedHashSet<>(new TreeSet<>(expected));
        missing.removeAll(known);
        if (missing.isEmpty()) {
            return Finding.pass("数据库迁移", "库里已到 V" + highest(known) + "，与 classpath 一致（"
                    + expected.size() + " 个迁移都跑过）");
        }
        String fix = "跑一次迁移（用 local profile 起一次应用，它会执行 db/migration 下没跑的脚本）";
        String detail = "库里到 V" + (known.isEmpty() ? "（一个都没跑）" : highest(known))
                + "，还有 " + missing.size() + " 个没跑：V" + join(missing);
        if (!unparsed.isEmpty()) {
            detail = detail + "；另有读不懂的版本号 " + unparsed;
        }
        return Finding.fail("数据库迁移", detail, fix);
    }

    private static List<Integer> readClasspathVersions() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/*.sql");
            Set<Integer> versions = new TreeSet<>();
            for (Resource resource : resources) {
                String name = resource.getFilename();
                if (name == null) {
                    continue;
                }
                Matcher matcher = FILE_VERSION.matcher(name);
                if (matcher.matches()) {
                    versions.add(Integer.parseInt(matcher.group(1)));
                }
            }
            return List.copyOf(versions);
        }
        catch (IOException unreadable) {
            throw new IllegalStateException("读不到 classpath 上的 db/migration：", unreadable);
        }
    }

    private static List<String> readAppliedVersions(Environment environment) throws SQLException {
        String url = environment.getProperty("spring.datasource.url");
        String user = environment.getProperty("spring.datasource.username", "");
        String password = environment.getProperty("spring.datasource.password", "");
        DriverManager.setLoginTimeout(3);
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "select version from flyway_schema_history where success = true")) {
            List<String> versions = new ArrayList<>();
            while (rows.next()) {
                versions.add(rows.getString(1));
            }
            return versions;
        }
    }

    private static boolean missingTable(SQLException failure) {
        return "42P01".equals(failure.getSQLState())
                || (failure.getMessage() != null && failure.getMessage().contains("flyway_schema_history"));
    }

    private static Integer leadingNumber(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = LEADING_DIGITS.matcher(raw.strip());
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static int highest(Set<Integer> versions) {
        return versions.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private static String join(Set<Integer> versions) {
        StringBuilder text = new StringBuilder();
        for (Integer version : versions) {
            if (!text.isEmpty()) {
                text.append(", V");
            }
            text.append(version);
        }
        return text.toString();
    }

    private static String oneLine(String message) {
        return message == null ? "（没有更多信息）" : message.replace('\n', ' ').strip();
    }

    /** 库里已成功的版本。抽成接口只为一件事：让"落后了""一致了"这两条能脱离真库去断言。 */
    @FunctionalInterface
    interface AppliedVersions {

        List<String> read(Environment environment) throws SQLException;
    }
}
