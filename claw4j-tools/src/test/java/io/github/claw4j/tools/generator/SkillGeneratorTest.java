package io.github.claw4j.tools.generator;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.tools.script.ScriptPackage;
import io.github.claw4j.tools.script.ScriptPackageRegistry;
import io.github.claw4j.tools.script.ScriptPackageToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 五步链路：生成 → 静态检查 → 沙箱自测 → 审计 → 人审通过才注册。
 *
 * <p>每一步的产物都要看得见，所以每一条用例断言的都是"这一步留下了什么"，
 * 而不是"这一步跑过了"。后者是永真的陷阱：
 * 一条只断言"状态是 PENDING"的用例，在"什么都不做就返回待审"的实现下也是绿的。
 */
class SkillGeneratorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);

    private static final Principal ETHAN =
            Principal.of("ethan", "admin", java.util.Set.of("*"), java.util.Set.of("*"));

    @TempDir
    Path parent;

    // ------------------------------------------------------------------ 第 1、2、3 步

    @Test
    @DisplayName("一份正常的脚本走完三步：落盘、自测通过、审计有两条")
    void 五步走完前四步() {
        Fixture fixture = fixture("wordcount");
        String marker = "mk-" + Long.toHexString(System.nanoTime());

        SkillGenerator.Proposal proposal = fixture.generator.propose(ETHAN, new SkillGenerator.Request(
                "wordcount", "数一数传入文本有多少个字符",
                "#!/bin/sh\nprintf '%s' \"$1\" | wc -c | tr -d ' '\necho " + marker + "\n"));

        // 1) 生成：落盘在暂存区
        assertThat(proposal.status()).isEqualTo(SkillGenerator.Status.PENDING_REVIEW);
        assertThat(proposal.candidate()).isPresent();
        assertThat(Files.isRegularFile(proposal.candidate().orElseThrow().resolve("run.sh"))).isTrue();
        assertThat(proposal.sha256()).isNotBlank();

        // 2) 静态检查
        assertThat(proposal.staticCheck().accepted()).isTrue();

        // 3) 沙箱自测：脚本真跑过，输出里有那个随机 marker
        assertThat(proposal.selfTest()).isPresent();
        assertThat(proposal.selfTest().orElseThrow().succeeded()).isTrue();
        assertThat(proposal.selfTest().orElseThrow().stdout()).contains(marker);

        // 4) 审计：生成与自测各一条
        assertThat(fixture.audit.replay("wordcount"))
                .extracting(entry -> entry.stage())
                .contains(GenerationAudit.Stage.GENERATED, GenerationAudit.Stage.SELF_TESTED);
    }

    /**
     * 静态检查没过 → 不落盘。
     *
     * <p>这条钉的是"检查在落盘之前"：顺序反了的话，危险代码先被写下来再被删掉，
     * 而"磁盘上出现过它"这件事没人会记录。
     */
    @Test
    @DisplayName("静态检查没过的脚本不落盘（危险代码不先写下来再删）")
    void 静态检查没过不落盘() {
        Fixture fixture = fixture("netprobe");

        SkillGenerator.Proposal proposal = fixture.generator.propose(ETHAN, new SkillGenerator.Request(
                "netprobe", "查一下外网能不能通", "#!/bin/sh\ncurl http://example.com\n"));

        assertThat(proposal.status()).isEqualTo(SkillGenerator.Status.REJECTED);
        assertThat(proposal.candidate()).isEmpty();
        assertThat(fixture.staging.resolve("netprobe")).doesNotExist();
        assertThat(fixture.audit.replay("netprobe"))
                .extracting(entry -> entry.stage())
                .contains(GenerationAudit.Stage.STATIC_REJECTED);
        assertThat(proposal.render()).contains("NETWORK_CLIENT");
    }

    /** 自测失败时把脚本的原话给回去，不包装——包装过的话里没有可改的信息。 */
    @Test
    @DisplayName("自测失败时回执里是脚本的原话")
    void 失败时给脚本原话() {
        Fixture fixture = fixture("broken");
        String marker = "boom-" + Long.toHexString(System.nanoTime());

        SkillGenerator.Proposal proposal = fixture.generator.propose(ETHAN, new SkillGenerator.Request(
                "broken", "一个跑不起来的脚本", "#!/bin/sh\necho " + marker + " 1>&2\nexit 2\n"));

        SkillGenerator.SelfTestResult selfTest = proposal.selfTest().orElseThrow();
        assertThat(selfTest.succeeded()).isFalse();
        assertThat(selfTest.stderr()).isEqualTo(marker);
        // 原话要出现在给模型的回执里，且没有经过包装
        assertThat(selfTest.render()).contains(marker).contains("退出码 2");
    }

    // ------------------------------------------------------------------ 第 5 步：人审

    /** 未人审：它不在工具清单里。这条就是票面上"用 01 那条清单断言验"的那一条。 */
    @Test
    @DisplayName("未人审的脚本不在工具清单里")
    void 未人审不在清单里() {
        Fixture fixture = fixture("wordcount");
        fixture.generator.propose(ETHAN, new SkillGenerator.Request(
                "wordcount", "数一数传入文本有多少个字符", "#!/bin/sh\necho 12\n"));

        assertThat(fixture.registry.contains("wordcount")).isFalse();
        assertThat(fixture.provider.toolNames()).doesNotContain("wordcount");
        assertThat(fixture.provider.permissions()).doesNotContainKey("wordcount");
    }

    @Test
    @DisplayName("人审通过之后才进清单，且审计记下是谁签的")
    void 人审通过才注册() throws IOException {
        Fixture fixture = fixture("wordcount");
        String script = "#!/bin/sh\necho 12\n";
        fixture.generator.propose(ETHAN, new SkillGenerator.Request("wordcount", "数字符", script));
        assertThat(fixture.provider.toolNames()).doesNotContain("wordcount");

        new ReviewGate().approve(fixture.reviews, "wordcount", "ethan", CLOCK.instant(), script);
        List<String> published = fixture.generator.publishApproved();

        assertThat(published).contains("wordcount");
        assertThat(fixture.registry.contains("wordcount")).isTrue();
        assertThat(fixture.provider.toolNames()).contains("wordcount");
        assertThat(fixture.provider.permissions()).containsKey("wordcount");
        assertThat(fixture.audit.replay("wordcount"))
                .extracting(entry -> entry.stage())
                .contains(GenerationAudit.Stage.APPROVED, GenerationAudit.Stage.PUBLISHED);
        assertThat(fixture.audit.replay("wordcount"))
                .anySatisfy(entry -> assertThat(entry.principal()).isEqualTo("ethan"));
    }

    /** 审过之后脚本被改 → 那次签字作废，不允许带着旧签字上线新版本。 */
    @Test
    @DisplayName("签字之后脚本被改，旧签字作废")
    void 签字版本对不上就不注册() throws IOException {
        Fixture fixture = fixture("wordcount");
        String first = "#!/bin/sh\necho 12\n";
        fixture.generator.propose(ETHAN, new SkillGenerator.Request("wordcount", "数字符", first));
        new ReviewGate().approve(fixture.reviews, "wordcount", "ethan", CLOCK.instant(), first);

        // 同名的包被重新生成了一版
        fixture.generator.propose(ETHAN, new SkillGenerator.Request("wordcount", "数字符",
                first + "echo 第二版\n"));

        assertThat(fixture.generator.publishApproved()).doesNotContain("wordcount");
        assertThat(fixture.provider.toolNames()).doesNotContain("wordcount");
        assertThat(fixture.audit.replay("wordcount"))
                .anySatisfy(entry -> assertThat(entry.detail()).contains("签字与当前脚本不一致"));
    }

    /** 已注册的包能被真的执行：注册与执行后端是同一件事（漏了后端会出现"清单有、跑不了"）。 */
    @Test
    @DisplayName("人审通过的包能真的跑起来")
    void 注册后能执行() throws IOException {
        Fixture fixture = fixture("marker");
        String script = "#!/bin/sh\necho ran-ok\n";
        fixture.generator.propose(ETHAN, new SkillGenerator.Request("marker", "取个标记", script));
        new ReviewGate().approve(fixture.reviews, "marker", "ethan", CLOCK.instant(), script);
        fixture.generator.publishApproved();

        ScriptPackage pkg = fixture.registry.packages().stream()
                .filter(candidate -> candidate.name().equals("marker"))
                .findFirst().orElseThrow();
        assertThat(new io.github.claw4j.tools.script.ScriptPackageRunner(List.of(pkg)).run("marker", List.of()))
                .contains("ran-ok");
    }

    // ------------------------------------------------------------------ 输入校验

    @Test
    @DisplayName("包名不合格 / 没描述 / 空脚本都在第一步被拒")
    void 输入不合格在第一步被拒() {
        Fixture fixture = fixture("bad");

        assertThat(fixture.generator.propose(ETHAN, new SkillGenerator.Request("bad name!", "d", "echo 1"))
                .status()).isEqualTo(SkillGenerator.Status.REJECTED);
        assertThat(fixture.generator.propose(ETHAN, new SkillGenerator.Request("nodesc", " ", "echo 1"))
                .status()).isEqualTo(SkillGenerator.Status.REJECTED);
        assertThat(fixture.generator.propose(ETHAN, new SkillGenerator.Request("empty", "d", " "))
                .status()).isEqualTo(SkillGenerator.Status.REJECTED);
        assertThat(fixture.staging.resolve("bad")).doesNotExist();
    }

    /** 重名注册直接失败：两个同名工具会让模型分不清该调哪个。 */
    @Test
    @DisplayName("清单里已有同名包时，重复注册直接失败")
    void 同名注册失败() {
        ScriptPackageRegistry registry = ScriptPackageRegistry.empty();
        Path root = parent.resolve("dup");
        assertThatThrownBy(() -> {
            registry.register(ScriptPackage.of("dup", java.nio.file.Files.createDirectories(root), "d"));
            registry.register(ScriptPackage.of("dup", root, "d"));
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("dup");
    }

    /** 清单被追加之后，模型那一侧的清单与权限表要一起变。 */
    @Test
    @DisplayName("后加的包同时出现在清单与权限表里")
    void 清单与权限一起变() throws IOException {
        ScriptPackageRegistry registry = ScriptPackageRegistry.empty();
        ScriptPackageToolProvider provider = new ScriptPackageToolProvider(registry,
                new io.github.claw4j.tools.script.ScriptPackageRunner(List.of()));
        Path root = Files.createDirectories(parent.resolve("late"));
        Files.writeString(root.resolve("run.sh"), "#!/bin/sh\necho hi\n");

        registry.register(ScriptPackage.of("late", root, "d"));

        assertThat(provider.toolNames()).contains("late");
        assertThat(provider.permissions()).containsKey("late");
        assertThat(provider.permissions().get("late")).isEqualTo(
                io.github.claw4j.domain.principal.ToolPermission.of(ScriptPackageToolProvider.SCRIPT_ACTION));
    }

    // ------------------------------------------------------------------ 辅助

    private Fixture fixture(String name) {
        Path staging = parent.resolve("staging-" + name);
        Path reviews = parent.resolve("reviews-" + name);
        ScriptPackageRegistry registry = ScriptPackageRegistry.empty();
        GenerationAudit audit = new GenerationAudit(parent.resolve("audit-" + name + ".tsv"));
        SkillGenerator generator = new SkillGenerator(staging, reviews, registry, audit,
                IsolationSpec.local(), CLOCK);
        return new Fixture(staging, reviews, registry, audit, generator,
                new ScriptPackageToolProvider(registry,
                        new io.github.claw4j.tools.script.ScriptPackageRunner(List.of())));
    }

    private record Fixture(Path staging, Path reviews, ScriptPackageRegistry registry, GenerationAudit audit,
                           SkillGenerator generator, ScriptPackageToolProvider provider) {
    }
}
