package io.github.claw4j.eval;

import io.github.claw4j.agent.toolaccess.ToolSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把产品里那句系统提示导出成 bfcl 能读的文件（票 30）。
 *
 * <p>为什么不在评测侧手抄一份：手抄的后果票 24 已经吃过——评测那份和产品那份各自演化，
 * 量出来的分与线上无关，而"我们明明改了提示词，分数怎么没动"这种问题最难查。
 * 所以这里的唯一职责是**把 {@link ToolSearch#SYSTEM_SUFFIX} 原样落到磁盘**，
 * {@code eval/tools/bfcl/run.sh --with-prompt} 读的就是它。
 *
 * <p>写成文件而不是环境变量：bfcl 是一个独立的 Python 进程，它读不到 JVM 里的常量；
 * 一份文件是两者之间唯一不需要序列化的接口。
 *
 * <p>跑法：{@code ./mvnw -o -Peval -pl claw4j-eval -am test -Dtest=SystemPromptExportTest}。
 * 改完 {@code SYSTEM_SUFFIX} 要重跑一次，否则 bfcl 量的是上一版提示词。
 */
@Tag("eval")
class SystemPromptExportTest {

    /** bfcl 侧读的就是这个路径（`run.sh` 里写死的相对仓库根的路径）。 */
    private static final String TARGET = "eval/tools/bfcl/system-prompt.txt";

    @Test
    @DisplayName("把 SYSTEM_SUFFIX 原样导出，并读回来逐字比对（票 30 / 31）")
    void exportsTheSuffixVerbatim() throws IOException {
        String suffix = suffix();

        assertThat(suffix)
                .as("空提示词挂上去等于裸跑，两次结果一样却会得出「提示词没用」的结论")
                .isNotBlank();

        Path out = TestPaths.repositoryRoot().resolve(TARGET);
        Files.createDirectories(out.getParent());
        Files.writeString(out, suffix, StandardCharsets.UTF_8);

        assertThat(Files.readString(out, StandardCharsets.UTF_8))
                .as("读回来必须逐字相同：编码错了、末尾换行被吃了，都会让 bfcl 量的不是那句提示词")
                .isEqualTo(suffix);
        assertThat(Files.size(out))
                .as("UTF-8 下中文占三字节，文件太小多半是写空了")
                .isGreaterThan(50L);

        System.out.printf("已导出 %s（%d 字符，%d 字节）%n", out, suffix.length(), Files.size(out));
    }

    /**
     * 用反射读，不直接写 {@code ToolSearch.SYSTEM_SUFFIX}。
     *
     * <p>它不是修辞上的洁癖：{@code public static final String} 由字面量拼成，是**编译期常量**，
     * javac 会把值**内联**进每一个引用点。于是改了 {@code ToolSearch} 之后，只要这个测试类
     * 自己没被重编（增量编译只看源码有没有变，看不到"依赖的常量变了"），跑出来导出的仍是
     * 上一版文案——而测试会绿、文件会写、`--with-prompt` 会照常跑，量的却是已经不存在的提示词。
     *
     * <p>2026-11-27（票 31）踩过一次：改了四句文案，导出的还是旧的三句，287 字节一个不差。
     * 反射取值在运行时解析，绕开内联，才叫"读真值"。
     */
    private static String suffix() {
        try {
            return (String) ToolSearch.class.getField("SYSTEM_SUFFIX").get(null);
        }
        catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "读不到 ToolSearch.SYSTEM_SUFFIX：字段被改名或降级成非 public，导出就没有对象了", failure);
        }
    }
}
