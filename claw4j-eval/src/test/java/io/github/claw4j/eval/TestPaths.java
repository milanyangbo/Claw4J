package io.github.claw4j.eval;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试里要用仓库根时统一从这里拿。
 *
 * <p>不写死 {@code ../}：surefire 的工作目录是模块目录，但有人在 IDE 里从根跑，两者差一级。
 * 找标记比猜路径可靠，标记取 {@code eval/rag/gold.yaml} 与 {@code pom.xml} 两个——
 * 单看一个都可能撞到别的目录。
 *
 * <p>原先这个方法是挂在通用评测的用例校验类上的，那一套删掉之后挪到这里，
 * 免得"某个测试类顺手提供工具方法"变成隐式约定。
 */
final class TestPaths {

    private TestPaths() {
    }

    /**
     * 从当前工作目录往上找仓库根。
     *
     * @throws IllegalStateException 一路到顶都没找到（工作目录不在仓库里）
     */
    static Path repositoryRoot() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            if (Files.exists(directory.resolve("eval/rag/gold.yaml"))
                    && Files.exists(directory.resolve("pom.xml"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "找不到仓库根：从当前工作目录往上没有 eval/rag/gold.yaml");
    }
}
