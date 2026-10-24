package io.github.claw4j.tools.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人审那一关：这一步必须是人，代码只负责读出决定并核对版本。
 *
 * <p>"看着像通过"不算通过，是这份测试反复钉的那一条：
 * {@code status} 写错一个字母、漏了 {@code by}、时间写不成时间——
 * 三种情形的后果都是不放行，因为它们答不出"谁在什么时候审了哪一版"。
 */
class ReviewGateTest {

    private static final String SCRIPT = "#!/bin/sh\necho hello\n";

    private final ReviewGate gate = new ReviewGate();

    @TempDir
    Path parent;

    @Test
    @DisplayName("没人签字就是没通过")
    void 没有标记就是没通过() {
        assertThat(gate.read(parent, "wordcount")).isEmpty();
    }

    @Test
    @DisplayName("签字通过：status/by/at/sha256 齐全才算")
    void 签字齐全才算通过() throws IOException {
        Path file = gate.approve(parent, "wordcount", "ethan", Instant.parse("2026-10-24T10:00:00Z"), SCRIPT);

        Optional<ReviewGate.Decision> decision = gate.read(parent, "wordcount");

        assertThat(decision).isPresent();
        assertThat(decision.get().by()).isEqualTo("ethan");
        assertThat(decision.get().sha256()).isEqualTo(ReviewGate.digest(SCRIPT));
        assertThat(gate.matches(decision.get(), SCRIPT)).isTrue();
        assertThat(file).exists();
    }

    /** 写错一个字母不算：放行这件事上"差不多"不能算数。 */
    @Test
    @DisplayName("status 写成别的（approve / yes / 空）都不算通过")
    void status写错不算通过() throws IOException {
        for (String status : new String[]{"approve", "yes", "APPROVED", "", "approved "}) {
            Path dir = Files.createDirectories(parent.resolve(status.isBlank() ? "blank" : status));
            Files.writeString(ReviewGate.fileOf(dir, "pkg"),
                    "status=" + status + "\nby=ethan\nat=2026-10-24T10:00:00Z\nsha256="
                            + ReviewGate.digest(SCRIPT) + "\n",
                    StandardCharsets.UTF_8);

            assertThat(gate.read(dir, "pkg")).as("status=%s 不该被当成通过", status).isEmpty();
        }
    }

    @Test
    @DisplayName("缺 by（不知道是谁签的）不算通过")
    void 缺签名人不算通过() throws IOException {
        Path dir = Files.createDirectories(parent.resolve("noby"));
        Files.writeString(ReviewGate.fileOf(dir, "pkg"),
                "status=approved\nby=\nat=2026-10-24T10:00:00Z\nsha256=" + ReviewGate.digest(SCRIPT) + "\n",
                StandardCharsets.UTF_8);

        assertThat(gate.read(dir, "pkg")).isEmpty();
    }

    @Test
    @DisplayName("时间写不成时间不算通过")
    void 时间非法不算通过() throws IOException {
        Path dir = Files.createDirectories(parent.resolve("badtime"));
        Files.writeString(ReviewGate.fileOf(dir, "pkg"),
                "status=approved\nby=ethan\nat=昨天\nsha256=" + ReviewGate.digest(SCRIPT) + "\n",
                StandardCharsets.UTF_8);

        assertThat(gate.read(dir, "pkg")).isEmpty();
    }

    /**
     * 脚本在签字之后被改过 → 那次签字作废。
     *
     * <p>这条防的是"审过一次"变成一张长期通行证：模型重新生成同名包，
     * 内容换了，而旧的签字文件还在——没有摘要比对的话，新版本是带着旧签字上线的。
     */
    @Test
    @DisplayName("脚本被改过之后，旧签字自动作废")
    void 脚本改过之后签字作废() throws IOException {
        gate.approve(parent, "pkg", "ethan", Instant.parse("2026-10-24T10:00:00Z"), SCRIPT);
        ReviewGate.Decision decision = gate.read(parent, "pkg").orElseThrow();

        String changed = SCRIPT + "echo another line\n";

        assertThat(gate.matches(decision, changed)).isFalse();
    }

    /** 人可以用编辑器写这个文件，测试用 approve() 写；两种写法读的是同一个格式。 */
    @Test
    @DisplayName("人手写的标记与 approve() 写出来的一样能被读回")
    void 手写标记也能被读回() throws IOException {
        Path dir = Files.createDirectories(parent.resolve("handwritten"));
        Files.writeString(ReviewGate.fileOf(dir, "pkg"), String.join("\n",
                "status=approved",
                "by=ethan",
                "at=2026-10-24T10:00:00Z",
                "sha256=" + ReviewGate.digest(SCRIPT),
                ""), StandardCharsets.UTF_8);

        assertThat(gate.read(dir, "pkg")).isPresent();
    }
}
