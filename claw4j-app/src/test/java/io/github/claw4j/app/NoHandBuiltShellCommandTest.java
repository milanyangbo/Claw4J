package io.github.claw4j.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主代码里不许出现手写的 shell 调用（ADR-0002）。
 *
 * <p>{@code AppArchitectureTest} 拦的是 API 层面的 {@code Runtime.exec} 与
 * {@code ProcessBuilder}；这一条拦的是<b>字符串</b>层面的东西：
 * 手写一个 {@code /bin/sh -c ...} 传进任何执行器，起进程的就不是本项目，
 * 于是那条 API 规则看不见它，而宿主机的命令行照样交出去了。
 * 两条合起来才把"Java 侧不拼命令行"这句话说完。
 *
 * <p>为什么是扫源码而不是扫字节码里的常量池：这里要挡的是"人写下了这段代码"，
 * 源码是最直白的证据，报错信息也直接指向那一行。
 */
class NoHandBuiltShellCommandTest {

    /**
     * 这些写法都会把一个 shell 变成命令的解释器，而 shell 会把参数里的分号
     * 当成第二条命令——1.x 正是这么把宿主机交出去的。
     *
     * <p>注释与 Javadoc 里同样不许出现这些字面量：扫的是整份文件，
     * 区分"代码里的字符串"和"注释里的说明"要做语法解析，而那会让这条规则
     * 变成一件需要维护的东西。代价是本项目的注释得换个说法，那是划算的。
     */
    private static final List<String> FORBIDDEN = List.of("sh -c", "bash -c", "/bin/sh", "/bin/bash", "cmd.exe");

    @Test
    @DisplayName("claw4j-* 各模块的主代码里没有手写的 shell 调用")
    void 主代码里没有手写shell调用() throws IOException {
        Path repository = Path.of("..").toRealPath();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repository)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                if (!module.getFileName().toString().startsWith("claw4j-")) {
                    continue;
                }
                Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(sources)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                        String content = Files.readString(file, StandardCharsets.UTF_8);
                        for (String forbidden : FORBIDDEN) {
                            if (content.contains(forbidden)) {
                                offenders.add(repository.relativize(file) + " 里出现了 " + forbidden);
                            }
                        }
                    }
                }
            }
        }

        assertThat(offenders).isEmpty();
    }
}
