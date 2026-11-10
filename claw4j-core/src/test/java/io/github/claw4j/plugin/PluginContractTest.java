package io.github.claw4j.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件版本契约的判定（P16）：区间怎么读、版本怎么比、哪一侧失败。
 *
 * <p>这一份只验纯计算，不碰 classpath：读文件、扫 jar、让启动停下来在 {@code claw4j-app} 的
 * {@code BuildContractTest} 里。
 *
 * <p>用 JUnit 的断言而不是 AssertJ：core 不依赖 AssertJ（它连 Spring 都不依赖）。
 */
class PluginContractTest {

    private static final CoreVersion CORE = CoreVersion.parse("0.1.0-SNAPSHOT");

    private static Properties declared(String name, String requiresCore) {
        Properties properties = new Properties();
        if (name != null) {
            properties.setProperty(PluginContract.KEY_NAME, name);
        }
        if (requiresCore != null) {
            properties.setProperty(PluginContract.KEY_REQUIRES_CORE, requiresCore);
        }
        return properties;
    }

    private static List<PluginContract.Violation> check(Properties... descriptors) {
        Map<String, Properties> files = new LinkedHashMap<>();
        for (int i = 0; i < descriptors.length; i++) {
            files.put("jar:file:/plugins/p" + i + ".jar!/" + PluginContract.FILE, descriptors[i]);
        }
        return PluginContract.checkAll(CORE, "0.1.0-SNAPSHOT", files);
    }

    @Test
    @DisplayName("区间内的版本通过")
    void acceptsAVersionInsideTheRange() {
        // 这里的核心版本是 0.1.0-SNAPSHOT。预发布为什么算作 0.1.0 由下一条专门验，
        // 这条只证明"区间本身是放行的"。
        assertEquals(List.of(), check(declared("sales", ">=0.1.0 <0.2.0")));
    }

    @Test
    @DisplayName("预发布按同号正式版比：0.1.0-SNAPSHOT 算满足 >=0.1.0，0.2.0-SNAPSHOT 会被同一个区间接住")
    void treatsASnapshotAsItsRelease() {
        // 方向一：同一个 reactor 里构建的插件不该被自己的 SNAPSHOT 挡住
        assertEquals(List.of(), check(declared("sales", ">=0.1.0 <0.2.0")));

        // 方向二：破坏性版本的预发布版不该被旧插件当成兼容
        List<PluginContract.Violation> broken =
                PluginContract.checkAll(CoreVersion.parse("0.2.0-SNAPSHOT"), "0.2.0-SNAPSHOT",
                        Map.of("source", declared("sales", ">=0.1.0 <0.2.0")));
        assertEquals(1, broken.size());
        assertTrue(broken.get(0).message().contains("0.2.0"), broken.get(0).message());
    }

    @Test
    @DisplayName("只比三段数字：缺的段补零，限定符不参与")
    void comparesOnlyTheNumericSegments() {
        assertEquals(CoreVersion.parse("0.1.0"), CoreVersion.parse("0.1"));
        assertTrue(CoreVersion.parse("0.1.0").compareTo(CoreVersion.parse("0.1.1")) < 0);
        assertTrue(CoreVersion.parse("0.1.1").compareTo(CoreVersion.parse("0.2.0")) < 0);
        assertEquals(0, CoreVersion.parse("0.1.0-SNAPSHOT").compareTo(CoreVersion.parse("0.1.0")));

        // 精确匹配也走同一套比较：裸版本 "0.1" 认 0.1.0-SNAPSHOT
        assertEquals(List.of(), check(declared("sales", "0.1")));
    }

    @Test
    @DisplayName("通配与单边区间")
    void supportsWildcardAndSingleBound() {
        assertEquals(List.of(), check(declared("sales", "*")));
        assertEquals(List.of(), check(declared("sales", "<0.2.0")));
        assertEquals(1, check(declared("sales", ">=0.2.0")).size(), "下界不够就该拒");
        assertEquals(1, check(declared("sales", ">0.1.0")).size(), "严格大于：0.1.0 不算进去");
    }

    @Test
    @DisplayName("裸版本是精确匹配：0.1.4 认不了 0.1.5")
    void aBareVersionMeansExactlyThatVersion() {
        assertEquals(List.of(), PluginContract.checkAll(
                CoreVersion.parse("0.1.4"), "0.1.4", Map.of("source", declared("sales", "0.1.4"))));
        assertEquals(1, PluginContract.checkAll(
                CoreVersion.parse("0.1.5"), "0.1.5", Map.of("source", declared("sales", "0.1.4"))).size());
    }

    @Test
    @DisplayName("逗号与空格一样：两种分隔写出来的区间是同一个意思")
    void commaSeparatedBoundsBehaveTheSameAsSpaces() {
        assertEquals(List.of(), PluginContract.checkAll(
                CoreVersion.parse("0.1.5"), "0.1.5", Map.of("source", declared("sales", ">=0.1.0,<0.2.0"))));
        assertEquals(1, PluginContract.checkAll(
                CoreVersion.parse("0.2.0"), "0.2.0", Map.of("source", declared("sales", ">=0.1.0,<0.2.0"))).size());
    }

    @Test
    @DisplayName("写错的比较符判不通过，不是当成没条件")
    void rejectsAMalformedTokenInsteadOfPassingIt() {
        List<PluginContract.Violation> violations = check(declared("sales", "=>0.1.0"));

        assertEquals(1, violations.size(), "=>0.1.0 是笔误，放过去等于闸没装");
        assertTrue(violations.get(0).message().contains("=>0.1.0"), violations.get(0).message());
        assertTrue(violations.get(0).message().contains("读不成一个区间"), violations.get(0).message());
    }

    @Test
    @DisplayName("版本段不是数字也判不通过")
    void rejectsANonNumericVersion() {
        assertEquals(1, check(declared("sales", ">=1.x")).size());
        assertEquals(1, check(declared("sales", ">=0.1.0.0")).size(), "四段数字不是一个版本号");

        // 全角数字：Character.isDigit 认它，Integer.parseInt 也认它（parseInt("１２３") == 123），
        // 于是旧写法下 "０.１.０" 会被读成 "0.1.0" 直接放行——不报错，只是判成了另一个版本。
        List<PluginContract.Violation> wide = check(declared("sales", ">=０.１.０"));
        assertEquals(1, wide.size());
        assertTrue(wide.get(0).message().contains("不是数字"), wide.get(0).message());
    }

    @Test
    @DisplayName("没写 requires-core 判不通过：不按「任意版本」兜底")
    void rejectsAMissingDeclaration() {
        List<PluginContract.Violation> absent = check(declared("sales", null));
        assertEquals(1, absent.size());
        assertTrue(absent.get(0).message().contains("没有声明 requires-core"), absent.get(0).message());

        // 写了键但值是空串，是同一件事：一个漏写的键被读成"什么都要"，等于在最重要的一行上放行
        assertEquals(1, check(declared("sales", "   ")).size());
    }

    @Test
    @DisplayName("一次报全部：升核心的人不想修一个重启一次")
    void reportsEveryMismatchedPluginInOnePass() {
        List<PluginContract.Violation> violations =
                check(declared("sales", ">=0.2.0"), declared("jira", ">=0.3.0"));

        assertEquals(2, violations.size());
        assertTrue(violations.get(0).message().contains("p0"), "按来源排序，顺序稳定");
        assertTrue(violations.get(1).message().contains("p1"));
    }

    @Test
    @DisplayName("消息里是原文与来源：重新拼一遍区间会和人写的对不上")
    void keepsTheRawDeclarationAndTheSource() {
        PluginContract.Violation violation = check(declared("sales", ">=0.2.0")).get(0);

        assertTrue(violation.message().contains("插件 sales"), violation.message());
        assertTrue(violation.message().contains(">=0.2.0"), violation.message());
        assertTrue(violation.message().contains("0.1.0-SNAPSHOT"), "要说清当前核心是哪个版本");
        assertTrue(violation.message().contains("p0.jar"), "要能定位到具体那个 jar");
    }

    @Test
    @DisplayName("没写 name 时仍然指得出是哪个文件，只是名字是占位符")
    void fallsBackToAPlaceholderWhenNameIsAbsent() {
        PluginContract.Violation violation = check(declared(null, ">=0.2.0")).get(0);

        assertTrue(violation.message().contains("（未具名）"), violation.message());
        assertTrue(violation.message().contains("jar:file:/plugins/p0.jar"), violation.message());
    }

    @Test
    @DisplayName("空版本号与非版本号都直接报错，不猜")
    void versionParsingRefusesToGuess() {
        assertThrows(IllegalArgumentException.class, () -> CoreVersion.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CoreVersion.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> CoreVersion.parse("v1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> CoreVersionRange.parse("  "));
    }
}
