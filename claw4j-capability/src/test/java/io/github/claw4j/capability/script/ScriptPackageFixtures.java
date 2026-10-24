package io.github.claw4j.capability.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * 造一个真能跑的脚本包：目录、入口脚本、可执行位。
 *
 * <p>可执行位是这套测试唯一的平台假设（CI 与本机都是 POSIX）。
 * 它值得写在这里而不是散在每个用例里：入口脚本<b>没有</b>可执行位时的症状是
 * {@code Permission denied}，而那句话出现在 stderr 里，离"忘了 chmod"隔了一层。
 */
final class ScriptPackageFixtures {

    private ScriptPackageFixtures() {
    }

    static ScriptPackage executable(String name, Path parent, String body) throws IOException {
        return executable(name, parent, ScriptPackage.DEFAULT_ENTRYPOINT, body,
                ScriptPackage.DEFAULT_TIMEOUT_MILLIS);
    }

    /** 自定义超时的那一版：入口仍用默认名。 */
    static ScriptPackage executable(String name, Path parent, String body, long timeoutMillis) throws IOException {
        return executable(name, parent, ScriptPackage.DEFAULT_ENTRYPOINT, body, timeoutMillis);
    }

    static ScriptPackage executable(String name, Path parent, String entrypoint, String body, long timeoutMillis)
            throws IOException {
        Path root = Files.createDirectories(parent.resolve(name));
        Path script = root.resolve(entrypoint);
        Files.createDirectories(script.getParent());
        Files.writeString(script, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE));
        return ScriptPackage.of(name, root, entrypoint, "测试用脚本包 " + name, timeoutMillis);
    }

    /** 沙箱里跑的是 {@code /bin/sh}，入口用它而不是 bash，免得本机与 CI 的 shell 不一致。 */
    static String script(String... lines) {
        return "#!/bin/sh\n" + String.join("\n", lines) + "\n";
    }
}
