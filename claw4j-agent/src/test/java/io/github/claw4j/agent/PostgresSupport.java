package io.github.claw4j.agent;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

/**
 * agent 侧数据库测试的共用样板：镜像名、可用性守卫、迁移与连接。
 *
 * <p>与 tools 模块里的 {@code PostgresTestSupport} 是同一套约定，只是各自模块都要有一份
 * （测试类不跨模块可见）。约定本身不要在这里改口径：镜像变量、跳过策略、
 * {@code -Dclaw4j.dbTests=required} 这三件事两边必须一致，
 * 否则"数据库测试到底跑了没有"会出现两种答案。
 */
final class PostgresSupport {

    private PostgresSupport() {
    }

    static PostgreSQLContainer<?> container() {
        return new PostgreSQLContainer<>(image())
                .withDatabaseName("claw4j")
                .withUsername("claw4j")
                .withPassword("claw4j");
    }

    static void migrate(PostgreSQLContainer<?> postgres) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
    }

    static DataSource dataSource(PostgreSQLContainer<?> postgres) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    static String imageName() {
        String configured = System.getenv("CLAW4J_POSTGRES_IMAGE");
        return configured == null || configured.isBlank() ? "pgvector/pgvector:pg16" : configured;
    }

    /** 镜像站的名字长得不像 postgres，不声明兼容会被 Testcontainers 在启动前拒绝。 */
    static DockerImageName image() {
        DockerImageName parsed = DockerImageName.parse(imageName());
        return parsed.getRepository().endsWith("postgres")
                ? parsed
                : parsed.asCompatibleSubstituteFor("postgres");
    }

    /**
     * 镜像不在本地就整类跳过；CI 上（{@code -Dclaw4j.dbTests=required}）跳过改成失败，
     * 因为跳过的绿和通过的绿在构建结果里长得一样。
     */
    static boolean imageAvailable(String testClassName) {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(imageName()).exec();
            return true;
        }
        catch (RuntimeException notAvailable) {
            String reason = "数据库测试跑不起来（" + notAvailable.getMessage() + "）。"
                    + "本机可以 docker pull " + imageName()
                    + "，或用 CLAW4J_POSTGRES_IMAGE 指向一个本地已有的镜像。";
            if ("required".equals(System.getProperty("claw4j.dbTests"))) {
                throw new IllegalStateException("[" + testClassName + "] " + reason
                        + " 当前 claw4j.dbTests=required，所以这里不跳过。");
            }
            System.err.println("[" + testClassName + "] " + reason + " 本次跳过。");
            return false;
        }
    }
}
