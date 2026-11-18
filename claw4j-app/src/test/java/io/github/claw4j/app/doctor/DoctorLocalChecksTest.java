package io.github.claw4j.app.doctor;

import io.github.claw4j.app.doctor.DoctorCheck.Finding;
import io.github.claw4j.app.doctor.DoctorCheck.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --doctor}：一次报全、逐项给修复动作、有 FAIL 就非 0。
 *
 * <p>用例分两路：检查本身（纯函数，喂 {@link MockEnvironment} 与假的历史表）与"起什么上下文"
 * （真起一次，断言没有 web、没有数据源）。第二路是这一层最容易长歪的地方——只要有人给它加上
 * {@code @SpringBootApplication}，doctor 就会跟着主应用一起起不来，而那正是它要查的场景。
 */
class DoctorLocalChecksTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("全配好时退出码 0，报告里一条 FAIL 都没有")
    void allGreenExitsZero() throws Exception {
        Path skills = Files.createDirectories(tmp.resolve("skills"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("claw4j.skills.directory", skills.toString())
                .withProperty("claw4j.model.deepseek.api-key", "secret:deepseek_api_key")
                .withProperty("DEEPSEEK_API_KEY", "value-from-environment")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/claw4j");

        Report report = run(Doctor.of(List.of(
                new MigrationCheck(() -> List.of(1, 2), env -> List.of("1", "2")),
                new DirectoryCheck(), new SecretHandleCheck(), new ConfigSanityCheck())), environment);

        assertThat(report.code).isZero();
        assertThat(report.text).contains("PASS").doesNotContain("FAIL");
    }

    @Test
    @DisplayName("目录配了但不存在：FAIL，且给出那个路径与怎么修")
    void missingDirectoryFailsWithFixHint() {
        Path missing = tmp.resolve("nope");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("claw4j.skills.directory", missing.toString());

        List<Finding> findings = new DirectoryCheck().run(environment);

        Finding skills = byItem(findings, "技能目录");
        assertThat(skills.status()).isEqualTo(Status.FAIL);
        assertThat(skills.detail()).contains(missing.toString());
        assertThat(skills.fix()).as("只报错不给动作，等于让人自己去猜").contains("建这个目录");
    }

    @Test
    @DisplayName("目录没配：SKIP 而不是 PASS——「没配」与「配对了」不是一件事")
    void unconfiguredDirectoryIsSkipped() {
        List<Finding> findings = new DirectoryCheck().run(new MockEnvironment());

        assertThat(findings).isNotEmpty().allSatisfy(finding ->
                assertThat(finding.status()).isEqualTo(Status.SKIP));
        assertThat(findings).anySatisfy(finding ->
                assertThat(finding.detail()).contains("claw4j.skills.directory"));
    }

    @Test
    @DisplayName("库里落后一个迁移：报出缺的是哪几个版本")
    void staleMigrationNamesMissingVersions() {
        Finding finding = MigrationCheck.compare(List.of(1, 2, 3, 4), List.of("1", "2", "4"));

        assertThat(finding.status()).isEqualTo(Status.FAIL);
        assertThat(finding.detail()).contains("V3").contains("1 个没跑");
    }

    @Test
    @DisplayName("库与 classpath 一致：PASS，并说清一共几个迁移")
    void upToDateMigrationPasses() {
        Finding finding = MigrationCheck.compare(List.of(1, 2, 3), List.of("1", "2", "3"));

        assertThat(finding.status()).isEqualTo(Status.PASS);
        assertThat(finding.detail()).contains("V3").contains("3 个迁移");
    }

    @Test
    @DisplayName("句柄在环境里取不到：FAIL，报的是环境变量名而不是句柄名")
    void unresolvableSecretHandleFails() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("claw4j.model.deepseek.api-key", "secret:deepseek_api_key");

        Finding finding = byItem(new SecretHandleCheck().run(environment), "deepseek");

        assertThat(finding.status()).isEqualTo(Status.FAIL);
        assertThat(finding.detail()).as("该去改的是环境变量，所以报变量名").contains("DEEPSEEK_API_KEY");
        assertThat(finding.fix()).contains("环境");
    }

    @Test
    @DisplayName("句柄能解析：PASS，而且只读了环境（没发任何请求）")
    void resolvableHandlePassesWithoutNetwork() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("claw4j.model.deepseek.api-key", "secret:deepseek_api_key")
                .withProperty("DEEPSEEK_API_KEY", "value-from-environment");

        Finding finding = byItem(new SecretHandleCheck().run(environment), "deepseek");

        assertThat(finding.status()).isEqualTo(Status.PASS);
        assertThat(finding.detail())
                .as("这一项只碰 Environment；真去问模型是 P18b 的事")
                .contains("没发请求");
    }

    @Test
    @DisplayName("backend=ssh：报「未实现」，不是静默忽略")
    void unimplementedBackendIsReported() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("claw4j.tools.sandbox.mode", "all")
                .withProperty("claw4j.tools.sandbox.backend", "ssh")
                .withProperty("claw4j.tools.sandbox.workspace-access", "rw");

        List<Finding> findings = new ConfigSanityCheck().run(environment);

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.status()).isEqualTo(Status.FAIL);
            assertThat(finding.detail()).contains("ssh").contains("没有实现");
        });
    }

    @Test
    @DisplayName("一项 FAIL 不影响后面几项照跑：这是「一次报全」的判据")
    void oneFailureDoesNotStopLaterChecks() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("claw4j.skills.directory", tmp.resolve("missing").toString())
                .withProperty("claw4j.model.deepseek.api-key", "secret:deepseek_api_key")
                .withProperty("DEEPSEEK_API_KEY", "value-from-environment");

        Report report = run(Doctor.of(List.of(new DirectoryCheck(), new SecretHandleCheck())), environment);

        assertThat(report.code).isEqualTo(1);
        assertThat(report.text).contains("FAIL").contains("不存在");
        assertThat(report.text())
                .as("第一项红了，第二项仍然要有结论——否则又退回成 fail-fast")
                .contains("能解析").contains("没发请求").contains("PASS");
    }

    @Test
    @DisplayName("doctor 的上下文没有 web、没有数据源：配置有问题时它也起得来")
    void doctorDoesNotStartWebServer() {
        try (ConfigurableApplicationContext context = Doctor.environment(new String[] {"--doctor"})) {
            assertThat(context.getBeanNamesForType(WebApplicationContext.class)).isEmpty();
            assertThat(context.getBeanNamesForType(DataSource.class))
                    .as("没有数据源就说明自动装配没跑——doctor 不该因为库连不上而自己起不来")
                    .isEmpty();
            assertThat(context.getEnvironment().getProperty("spring.application.name"))
                    .as("配置数据阶段照常生效，否则读不到 yml 里的键")
                    .isEqualTo("claw4j");
        }
    }

    private Report run(Doctor doctor, MockEnvironment environment) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = doctor.print(environment, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return new Report(code, buffer.toString(StandardCharsets.UTF_8));
    }

    private static Finding byItem(List<Finding> findings, String item) {
        return findings.stream().filter(finding -> finding.item().equals(item)).findFirst()
                .orElseThrow(() -> new AssertionError("报告里没有这一项：" + item + "，实际：" + findings));
    }

    /** 一次运行的产物：退出码与整份报告。 */
    private record Report(int code, String text) {
    }
}
