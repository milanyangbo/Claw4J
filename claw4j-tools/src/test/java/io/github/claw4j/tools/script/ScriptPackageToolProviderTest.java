package io.github.claw4j.tools.script;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 脚本层对 Agent 呈现的样子，以及它声明的边界。
 *
 * <p>这一组不跑脚本、不连模型：要验的是"模型看到的工具长什么样"和
 * "这一层自己声明了什么权限"。跑不跑得动由 {@code ScriptPackageRunnerTest} 管，
 * 模型会不会调由 agent 侧的端到端管。
 */
class ScriptPackageToolProviderTest {

    @Test
    @DisplayName("工具名就是包名，模型只看到名字与一句描述")
    void 工具名就是包名(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("weatherForecast", parent,
                ScriptPackageFixtures.script("echo sunny"));

        ScriptPackageToolProvider provider = new ScriptPackageToolProvider(List.of(pkg));

        assertThat(provider.toolNames()).containsExactly("weatherForecast");
        assertThat(provider.getToolCallbacks()[0].getToolDefinition().description())
                .contains("weatherForecast");
    }

    /**
     * 权限登记的形状就是这一层的边界：动作是跑脚本，**业务表一张都没有**。
     *
     * <p>"表是空的"不等于"不需要权限"——这两句在登记表上长得一模一样，
     * 区别只在于有没有人显式登记过。没登记过的工具一律按拒绝处理（ADR-0007），
     * 所以这一条断言盯的是"登记了"且"表是空的"这两件事同时成立。
     */
    @Test
    @DisplayName("登记的是跑脚本动作，业务表一张都没有")
    void 权限登记的形状(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("weatherForecast", parent,
                ScriptPackageFixtures.script("echo sunny"));

        ToolPermission permission = new ScriptPackageToolProvider(List.of(pkg))
                .permissions().get("weatherForecast");

        assertThat(permission).isNotNull();
        assertThat(permission.action()).isEqualTo(ScriptPackageToolProvider.SCRIPT_ACTION);
        assertThat(permission.tables()).isEmpty();
    }

    /** 只读角色能查数，但执行不了脚本：脚本能写文件、能往外连，是比读一张表宽得多的口子。 */
    @Test
    @DisplayName("只读角色跑不了脚本，授权里写了这个动作才可以")
    void 只读角色跑不了脚本(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("weatherForecast", parent,
                ScriptPackageFixtures.script("echo sunny"));
        ToolPermission permission = new ScriptPackageToolProvider(List.of(pkg))
                .permissions().get("weatherForecast");

        assertThat(Principal.readOnly("u-1").canUse(permission)).isFalse();
        assertThat(Principal.of("u-2", "admin", Set.of(Principal.WILDCARD),
                Set.of(ScriptPackageToolProvider.SCRIPT_ACTION)).canUse(permission)).isTrue();
    }

    @Test
    @DisplayName("包目录里的每个子目录都是一个包")
    void 目录加载(@TempDir Path root) throws IOException {
        writeManifest(root, "weather", "查询某地的天气", "forecast.sh", 12_000);
        ScriptPackageFixtures.executable("weather", root, "forecast.sh",
                ScriptPackageFixtures.script("echo sunny"), 12_000);

        List<ScriptPackage> packages = ScriptPackageCatalog.load(root);

        assertThat(packages).singleElement()
                .satisfies(pkg -> {
                    assertThat(pkg.name()).isEqualTo("weather");
                    assertThat(pkg.description()).isEqualTo("查询某地的天气");
                    assertThat(pkg.entrypoint()).isEqualTo("forecast.sh");
                    assertThat(pkg.timeoutMillis()).isEqualTo(12_000);
                });
    }

    /** 没有描述的包永远不会被调用，而且不会报错——所以它必须在启动时就失败。 */
    @Test
    @DisplayName("缺 description 的包在加载时就失败")
    void 缺描述即失败(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("weather"));
        Files.writeString(dir.resolve(ScriptPackageCatalog.MANIFEST), "entrypoint=forecast.sh",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ScriptPackageCatalog.load(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    /** 配了一个不存在的目录是配置错误，不是"这一层不存在"——启动就该炸。 */
    @Test
    @DisplayName("配了不存在的目录即失败")
    void 目录不存在即失败(@TempDir Path root) {
        assertThatThrownBy(() -> ScriptPackageCatalog.load(root.resolve("nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");
    }

    private static void writeManifest(Path root, String name, String description, String entrypoint,
                                      long timeoutMillis) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve(ScriptPackageCatalog.MANIFEST),
                "description=" + description + "\nentrypoint=" + entrypoint + "\ntimeoutMillis=" + timeoutMillis,
                StandardCharsets.UTF_8);
    }
}
