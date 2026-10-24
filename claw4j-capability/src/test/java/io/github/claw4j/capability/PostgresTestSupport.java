package io.github.claw4j.capability;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

/**
 * 数据库测试的共用样板：镜像名、可用性守卫、迁移与连接。
 *
 * <p><b>为什么抽出来</b>：可用性守卫里写着一条策略——镜像拉不到就整类跳过，
 * 但 CI 上（{@code -Dclaw4j.dbTests=required}）必须变红。这条策略有两个以上的测试类在用，
 * 复制两份就会变成"改了一处、另一处还是旧的"，而它的失效姿势是
 * "数据库测试悄悄没跑"，正是它自己要防的那种事。
 */
public final class PostgresTestSupport {

    private PostgresTestSupport() {
    }

    public static PostgreSQLContainer<?> container() {
        return new PostgreSQLContainer<>(image())
                .withDatabaseName("claw4j")
                .withUsername("claw4j")
                .withPassword("claw4j");
    }

    public static void migrate(PostgreSQLContainer<?> postgres) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
    }

    public static DataSource dataSource(PostgreSQLContainer<?> postgres) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    /**
     * 镜像名可被环境变量覆盖，跟 {@code compose.yaml} 用的是同一个变量。
     * 国内网络拉不动 Docker Hub 时设成镜像站即可，不必改代码。
     */
    private static String imageName() {
        String configured = System.getenv("CLAW4J_POSTGRES_IMAGE");
        return configured == null || configured.isBlank() ? "pgvector/pgvector:pg16" : configured;
    }

    /**
     * 镜像站的名字（{@code docker.m.daocloud.io/pgvector/pgvector}）长得不像 postgres，
     * Testcontainers 会在启动前就拒绝它。名字里不带 postgres 的都得显式声明
     * "这就是 postgres"，否则换了个拉取来源就跑不起来——这个坑只在换源时出现，
     * 用官方镜像的人永远撞不到。
     */
    private static DockerImageName image() {
        DockerImageName parsed = DockerImageName.parse(imageName());
        return parsed.getRepository().endsWith("postgres")
                ? parsed
                : parsed.asCompatibleSubstituteFor("postgres");
    }

    /**
     * Docker 守护进程在，就真跑；不在，才整类跳过。
     *
     * <p><b>这里早先判的是"本地有没有这个镜像的缓存"</b>（{@code inspectImageCmd}），
     * 那条判据是错的，而且错在最要命的方向上：它让"没拉过镜像"和"拉不到镜像"
     * 变成同一件事，于是只要这台机器没缓存过 {@code pgvector/pgvector:pg16}，
     * 所有数据库测试整类静默跳过——而跳过的绿和通过的绿在构建结果里长得一模一样。
     * 实测：本机没缓存时 26 条从未执行，其中正好包含只读约束、会话继承、
     * 启动迁移这三条 M1 最硬的判据。CI 上更糟：{@code -Dclaw4j.dbTests=required}
     * 会让守卫抛 {@code IllegalStateException}，整类变成 error，
     * 而不是让 Testcontainers 去把镜像拉下来。
     *
     * <p>正确的问题是"这台机器能不能跑容器"，不是"这个镜像在不在本地"——
     * 拉镜像本来就是 Testcontainers 自己的事（{@code PostgreSQLContainer} 启动时会 pull）。
     * 拉不动是环境问题，那应该红，不应该沉默。
     *
     * <p>{@code -Dclaw4j.dbTests=required} 保留，语义收窄成"连 Docker 都没有就别装绿"：
     * CI 上如果有机器没起 Docker，跳过会让人以为跑过了。
     */
    public static boolean databaseAvailable(String testClassName) {
        boolean dockerUp;
        try {
            dockerUp = DockerClientFactory.instance().isDockerAvailable();
        }
        catch (RuntimeException | LinkageError broken) {
            dockerUp = false;
        }
        if (dockerUp) {
            // 拉镜像是 Testcontainers 的事，但卡住的时候得让人知道卡在哪个镜像上——
            // 国内最常见的卡法就是 Docker Hub 超时，而换源只要一个环境变量。
            System.err.println("[" + testClassName + "] 正在用镜像 " + imageName()
                    + " 起数据库（需要时会现拉，第一次慢）。"
                    + "拉取超时请用 CLAW4J_POSTGRES_IMAGE 换镜像源。");
            return true;
        }
        String reason = "数据库测试跑不起来：这台机器上没有可用的 Docker 守护进程。"
                + "本机请启动 Docker，或用 CLAW4J_POSTGRES_IMAGE 指定镜像（当前是 " + imageName() + "）。";
        if ("required".equals(System.getProperty("claw4j.dbTests"))) {
            // 抛出去而不是返回 false：条件方法抛异常会让整个类变成 error，构建就红了。
            throw new IllegalStateException(reason + " 当前 claw4j.dbTests=required，"
                    + "所以这里不跳过——跳过的绿会让人以为数据库测试真的跑过。");
        }
        System.err.println("[" + testClassName + "] " + reason + " 本次跳过。");
        return false;
    }
}
