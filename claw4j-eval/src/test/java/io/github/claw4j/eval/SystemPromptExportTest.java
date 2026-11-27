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
    @DisplayName("把 SYSTEM_SUFFIX 原样导出，并读回来逐字比对（票 30）")
    void exportsTheSuffixVerbatim() throws IOException {
        String suffix = ToolSearch.SYSTEM_SUFFIX;

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
}
