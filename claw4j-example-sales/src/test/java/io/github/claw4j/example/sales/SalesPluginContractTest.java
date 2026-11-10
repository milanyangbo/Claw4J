package io.github.claw4j.example.sales;

import io.github.claw4j.plugin.CoreVersion;
import io.github.claw4j.plugin.PluginContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 这个插件声明的核心区间，必须接纳它自己编译时对着的那个核心（P16）。
 *
 * <p>放在这里而不只靠启动期校验：启动期拦的是部署时才发现，这一条拦的是提交时就发现。
 * 核心升了 minor 而没人回头改 {@code META-INF/claw4j-plugin.properties}，构建当场红。
 *
 * <p>不是把同一件事测两遍：启动期那份跑在别人的部署上，这一份跑在仓库里。
 */
class SalesPluginContractTest {

    @Test
    @DisplayName("我声明的核心区间接纳当前核心版本")
    void theDeclaredRangeAdmitsTheCoreThisPluginWasBuiltAgainst() throws IOException {
        String core = read("META-INF/claw4j-core.properties").getProperty("version");
        assertThat(core)
                .as("核心版本资源必须在这条 classpath 上；读不到说明 claw4j-core 的构建过滤没生效")
                .isNotBlank();

        Properties descriptor = read("META-INF/claw4j-plugin.properties");

        assertThat(PluginContract.checkAll(CoreVersion.parse(core), core, Map.of("example-sales", descriptor)))
                .as("示例插件与核心必须一起升级：改 requires-core，或者别升这个版本")
                .isEmpty();
    }

    private static Properties read(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = SalesPluginContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("classpath 上没有 " + resource).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
