package io.github.claw4j.tools.mcp.stdio;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 找到仓库里的 {@code examples/} 目录。
 *
 * <p>surefire 的工作目录是<b>模块目录</b>（{@code claw4j-tools}），不是仓库根，
 * 于是 {@code examples/mcp/...} 这种相对路径在测试里是错的，而写死
 * {@code ../examples/} 会在有人从根上跑（{@code -pl} 不带模块）时同样错。
 * 向上找是唯一两种情形都成立的写法。
 */
final class ExamplesDirectory {

    private ExamplesDirectory() {
    }

    /** @throws IllegalStateException 找不到：演示脚本不在原位时，该红的是这一条而不是某条断言 */
    static Path mcp() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int level = 0; level < 6 && current != null; level++, current = current.getParent()) {
            Path candidate = current.resolve("examples").resolve("mcp");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 examples/mcp 目录（从 " + System.getProperty("user.dir")
                + " 向上找了 6 层）。演示脚本被挪走了，那这批测试要跟着改。");
    }

    /** 子进程需要的环境：至少要有 PATH，否则连 python3 都找不到。 */
    static java.util.Map<String, String> pathOnly() {
        String path = System.getenv("PATH");
        return path == null ? java.util.Map.of() : java.util.Map.of("PATH", path);
    }
}
