package io.github.claw4j.capability.generator;

import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ProcessRequest;
import io.github.claw4j.capability.script.SandboxPolicy;
import io.github.claw4j.capability.script.ScriptPackage;
import io.github.claw4j.capability.script.ScriptPackageCatalog;
import io.github.claw4j.capability.script.ScriptPackageRegistry;
import io.github.claw4j.capability.script.ScriptPackageRunner;
import io.github.claw4j.capability.script.ScriptPackageToolProvider;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生成器这一层的一条链路：生成 → 静态检查 → 沙箱自测 → 审计 → 人审通过才注册。
 *
 * <p>每一步的产物都要看得见，所以断言的是"这一步留下了什么"，而不是"这一步跑过了"。
 * 后者是永真的陷阱：一条只断言"状态是 PENDING"的用例，在"什么都不做就返回待审"的实现下也绿。
 *
 * <p>这一层最贵的失效全是静默的：未人审的脚本进了清单、同名重提偷换了已上线那版、
 * 沙箱其实跑在候选目录里、人审标记写错一个字母也被当成通过。它们不报错，只是边界没了。
 */
class SkillGeneratorFlowTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);

    private static final Principal ETHAN =
            Principal.of("ethan", "admin", Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));

    private final ApplicationContextRunner scriptsOnly = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    io.github.claw4j.capability.script.ScriptPackageAutoConfiguration.class,
                    GeneratorAutoConfiguration.class));

    @TempDir
    Path parent;

    // ------------------------------------------------------------------ 五步

    @Test
    @DisplayName("一份正常的脚本走完前四步：落盘、静态通过、自测真跑、审计两条")
    void 五步走完前四步() {
        Fixture fixture = fixture("wordcount");
        String marker = "mk-" + Long.toHexString(System.nanoTime());

        SkillGenerator.Proposal proposal = fixture.generator.propose(ETHAN, new SkillGenerator.Request(
                "wordcount", "数一数传入文本有多少个字符",
                "#!/bin/sh\nprintf '%s' \"$1\" | wc -c | tr -d ' '\necho " + marker + "\n"));

        assertThat(proposal.status()).isEqualTo(SkillGenerator.Status.PENDING_REVIEW);
        assertThat(proposal.candidate()).isPresent();
        assertThat(Files.isRegularFile(proposal.candidate().orElseThrow().resolve("run.sh"))).isTrue();
        assertThat(proposal.sha256()).isNotBlank();

        assertThat(proposal.staticCheck().accepted()).isTrue();

        // 自测真跑过：输出里有那个随机 marker
        assertThat(proposal.selfTest()).isPresent();
        assertThat(proposal.selfTest().orElseThrow().succeeded()).isTrue();
        assertThat(proposal.selfTest().orElseThrow().stdout()).contains(marker);

        assertThat(fixture.audit.replay("wordcount"))
                .extracting(GenerationAudit.Entry::stage)
                .contains(GenerationAudit.Stage.GENERATED, GenerationAudit.Stage.SELF_TESTED);
    }

    /**
     * 静态检查没过就不落盘：顺序反了的话，危险代码先被写下来再被删掉，
     * 而"磁盘上出现过它"这件事没人会记录。
     */
    @Test
    @DisplayName("静态检查没过：不落盘，审计记 STATIC_REJECTED，回执带规则号")
    void 静态检查没过不落盘() {
        Fixture fixture = fixture("netprobe");

        SkillGenerator.Proposal proposal = fixture.generator.propose(ETHAN, new SkillGenerator.Request(
                "netprobe", "查一下外网能不能通", "#!/bin/sh\ncurl http://example.com\n"));

        assertThat(proposal.status()).isEqualTo(SkillGenerator.Status.REJECTED);
        assertThat(proposal.candidate()).isEmpty();
        assertThat(fixture.staging.resolve("netprobe")).doesNotExist();
        assertThat(fixture.audit.replay("netprobe"))
                .extracting(GenerationAudit.Entry::stage)
                .contains(GenerationAudit.Stage.STATIC_REJECTED);
        assertThat(proposal.render()).contains("NETWORK_CLIENT");
    }

    @Test
    @DisplayName("包名不合格、没描述、空脚本，都在第一步被拒且不落盘")
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

    /** 自测失败时把脚本的原话给回去，不包装——包装过的话里没有可改的信息。 */
    @Test
    @DisplayName("自测失败时回执里是脚本的原话：stderr 与退出码都在")
    void 失败时给脚本原话() {
        Fixture fixture = fixture("broken");
        String marker = "boom-" + Long.toHexString(System.nanoTime());

        SkillGenerator.Proposal proposal = fixture.generator.propose(ETHAN, new SkillGenerator.Request(
                "broken", "一个跑不起来的脚本", "#!/bin/sh\necho " + marker + " 1>&2\nexit 2\n"));

        SkillGenerator.SelfTestResult selfTest = proposal.selfTest().orElseThrow();
        assertThat(selfTest.succeeded()).isFalse();
        assertThat(selfTest.stderr()).isEqualTo(marker);
        assertThat(selfTest.render()).contains(marker).contains("退出码 2");
    }

    // ------------------------------------------------------------------ 人审闸门

    /**
     * 人审只认四项齐全：{@code status=approved}、谁签的、什么时候签的、签的是哪一版。
     * 缺一项或写错一项都是"不放行"，因为它们答不出"谁在什么时候审了哪一版"。
     */
    @Test
    @DisplayName("人审四项齐全才通过：status 写错、缺签名人、时间非法都不算，手写照同样能读回")
    void 人审四项齐全才通过() throws IOException {
        ReviewGate gate = new ReviewGate();
        String script = "#!/bin/sh\necho hello\n";
        String digest = ReviewGate.digest(script);

        assertThat(gate.read(parent, "wordcount")).isEmpty();

        gate.approve(parent, "wordcount", "ethan", CLOCK.instant(), script);
        ReviewGate.Decision decision = gate.read(parent, "wordcount").orElseThrow();
        assertThat(decision.by()).isEqualTo("ethan");
        assertThat(gate.matches(decision, script)).isTrue();

        for (String status : new String[]{"approve", "yes", "APPROVED", "", "approved "}) {
            Path dir = Files.createDirectories(parent.resolve(status.isBlank() ? "blank" : status));
            Files.writeString(ReviewGate.fileOf(dir, "pkg"),
                    "status=" + status + "\nby=ethan\nat=2026-10-24T10:00:00Z\nsha256=" + digest + "\n",
                    StandardCharsets.UTF_8);
            assertThat(gate.read(dir, "pkg")).as("status=%s 不该被当成通过", status).isEmpty();
        }

        Path noBy = Files.createDirectories(parent.resolve("noby"));
        Files.writeString(ReviewGate.fileOf(noBy, "pkg"),
                "status=approved\nby=\nat=2026-10-24T10:00:00Z\nsha256=" + digest + "\n", StandardCharsets.UTF_8);
        assertThat(gate.read(noBy, "pkg")).isEmpty();

        Path badTime = Files.createDirectories(parent.resolve("badtime"));
        Files.writeString(ReviewGate.fileOf(badTime, "pkg"),
                "status=approved\nby=ethan\nat=昨天\nsha256=" + digest + "\n", StandardCharsets.UTF_8);
        assertThat(gate.read(badTime, "pkg")).isEmpty();

        // 人可以用编辑器手写这个文件，读的是同一个格式
        Path handwritten = Files.createDirectories(parent.resolve("handwritten"));
        Files.writeString(ReviewGate.fileOf(handwritten, "pkg"), String.join("\n",
                "status=approved", "by=ethan", "at=2026-10-24T10:00:00Z", "sha256=" + digest, ""),
                StandardCharsets.UTF_8);
        assertThat(gate.read(handwritten, "pkg")).isPresent();
    }

    @Test
    @DisplayName("未人审不在清单；审过之后进清单、进权限表，审计记下谁签的，且能真的跑起来")
    void 人审通过才注册() throws IOException {
        Fixture fixture = fixture("marker");
        String script = "#!/bin/sh\necho ran-ok\n";
        fixture.generator.propose(ETHAN, new SkillGenerator.Request("marker", "取个标记", script));

        assertThat(fixture.registry.contains("marker")).isFalse();
        assertThat(fixture.provider.toolNames()).doesNotContain("marker");
        assertThat(fixture.provider.permissions()).doesNotContainKey("marker");

        new ReviewGate().approve(fixture.reviews, "marker", "ethan", CLOCK.instant(), script);

        assertThat(fixture.generator.publishApproved()).contains("marker");
        assertThat(fixture.registry.contains("marker")).isTrue();
        assertThat(fixture.provider.toolNames()).contains("marker");
        assertThat(fixture.provider.permissions()).containsKey("marker");
        assertThat(fixture.audit.replay("marker"))
                .extracting(GenerationAudit.Entry::stage)
                .contains(GenerationAudit.Stage.APPROVED, GenerationAudit.Stage.PUBLISHED);
        assertThat(fixture.audit.replay("marker"))
                .anySatisfy(entry -> assertThat(entry.principal()).isEqualTo("ethan"));

        // 注册与执行后端是同一件事：只进清单而跑不了，等于"清单有、跑不了"
        assertThat(run(fixture, "marker")).contains("ran-ok");
    }

    /**
     * 签字绑在脚本摘要上，不是绑在包名上。
     *
     * <p>这就是"人审闸门可以被绕过"那个形状：模型拿同一个名字再提一版（只需过静态检查），
     * 没有摘要比对的话，跑的是一份没人签过字的脚本，而人看到的"我审过"指向另一份。
     * 判据因此落在<b>执行结果</b>上，不是"清单里有没有它"。
     */
    @Test
    @DisplayName("签字后改了脚本：旧签字作废；同名重提跑的还是人审过那版；重新签字后才能上线")
    void 签字绑定摘要() throws IOException {
        Fixture fixture = fixture("swap");
        String v1 = "#!/bin/sh\necho V1\n";
        fixture.generator.propose(ETHAN, new SkillGenerator.Request("swap", "取个标记", v1));
        new ReviewGate().approve(fixture.reviews, "swap", "ethan", CLOCK.instant(), v1);
        assertThat(fixture.generator.publishApproved()).contains("swap");
        assertThat(run(fixture, "swap")).contains("V1");

        String v2 = "#!/bin/sh\necho V2\n";
        fixture.generator.propose(ETHAN, new SkillGenerator.Request("swap", "取个标记", v2));
        fixture.generator.publishApproved();

        assertThat(run(fixture, "swap")).contains("V1").doesNotContain("V2");
        assertThat(fixture.audit.replay("swap"))
                .anySatisfy(entry -> assertThat(entry.detail()).contains("签字与当前脚本不一致"));

        // 反面：快照不是永久冻结，新版重新签字后要能真的上线
        new ReviewGate().approve(fixture.reviews, "swap", "ethan", CLOCK.instant(), v2);
        assertThat(fixture.generator.publishApproved()).contains("swap");
        assertThat(run(fixture, "swap")).contains("V2").doesNotContain("V1");
    }

    // ------------------------------------------------------------------ 沙箱（未审核代码跑在哪儿）

    @Test
    @DisplayName("自测跑在临时副本里：写入不回候选目录，跑完即删")
    void 沙箱目录不是候选目录() throws IOException {
        Path candidate = candidate("writer", "#!/bin/sh\necho written > created.txt\npwd\n");

        GeneratorSandbox sandbox = GeneratorSandbox.forCandidate(candidate, IsolationSpec.local());
        ProcessOutcome outcome = sandbox.run(new ProcessRequest(
                List.of(sandbox.sandboxDirectory().resolve("run.sh").toString()), 1_000));

        assertThat(sandbox.sandboxDirectory()).isNotEqualTo(candidate);
        assertThat(outcome.stdout()).contains(sandbox.sandboxDirectory().toString());
        assertThat(candidate.resolve("created.txt")).doesNotExist();
        assertThat(Files.exists(sandbox.sandboxDirectory())).isFalse();
    }

    @Test
    @DisplayName("沙箱环境里没有应用进程的任何环境变量")
    void 环境不继承() throws IOException {
        Path candidate = candidate("env", "#!/bin/sh\nenv | sort\n");
        Set<String> shellGenerated = Set.of("PWD", "SHLVL", "_", "OLDPWD");
        Set<String> hostKeys = System.getenv().keySet().stream()
                .filter(key -> !"PATH".equals(key))
                .filter(key -> !shellGenerated.contains(key))
                .collect(Collectors.toSet());

        GeneratorSandbox sandbox = GeneratorSandbox.forCandidate(candidate, IsolationSpec.local());
        ProcessOutcome outcome = sandbox.run(new ProcessRequest(
                List.of(sandbox.sandboxDirectory().resolve("run.sh").toString()), 1_000));

        assertThat(hostKeys).as("宿主机上要有至少一个非 PATH 的变量，否则这条断言是永真的").isNotEmpty();
        for (String key : hostKeys) {
            assertThat(outcome.stdout()).doesNotContain(key + "=");
        }
        assertThat(outcome.stdout()).contains("PATH=");
    }

    /**
     * 规格不许撒谎：网络这一项在本地形态下是"没隔离"，而且必须写清为什么。
     * 把它改成 ISOLATED 会让规格看起来更漂亮，而"网络受限"从此有一段没有测试支撑的依据。
     */
    @Test
    @DisplayName("超自测上限被拒、root 拒跑；网络如实写「没隔离」；自测上限比脚本层更紧")
    void 沙箱规格与上限() throws IOException {
        Path candidate = candidate("spec", "#!/bin/sh\necho hi\n");
        GeneratorSandbox sandbox = GeneratorSandbox.forCandidate(candidate, IsolationSpec.local());
        ProcessRequest overCap = new ProcessRequest(
                List.of(sandbox.sandboxDirectory().resolve("run.sh").toString()), 60_000);

        assertThatThrownBy(() -> sandbox.run(overCap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自测超时");

        GeneratorSandbox asRoot = GeneratorSandbox.forCandidate(candidate, IsolationSpec.local(), () -> "root");
        assertThatThrownBy(() -> asRoot.run(new ProcessRequest(
                List.of(asRoot.sandboxDirectory().resolve("run.sh").toString()), 1_000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非 root");

        IsolationSpec spec = IsolationSpec.local();
        assertThat(spec.network()).isEqualTo(IsolationSpec.Network.NOT_ISOLATED);
        assertThat(spec.networkNote()).isNotBlank();
        assertThat(IsolationSpec.MAX_TIMEOUT_MILLIS).isLessThan(SandboxPolicy.MAX_TIMEOUT_MILLIS);
    }

    @Test
    @DisplayName("自测失败拿得到退出码与 stderr；缺 shebang 报的是能改的那句话")
    void 沙箱失败也说得明白() throws IOException {
        Path failing = candidate("failing", "#!/bin/sh\necho bad 1>&2\nexit 3\n");
        GeneratorSandbox sandbox = GeneratorSandbox.forCandidate(failing, IsolationSpec.local());
        ProcessOutcome outcome = sandbox.run(new ProcessRequest(
                List.of(sandbox.sandboxDirectory().resolve("run.sh").toString()), 2_000));
        assertThat(outcome.exitCode()).isEqualTo(3);
        assertThat(outcome.stderr().strip()).isEqualTo("bad");

        Path noShebang = candidate("noshebang", "echo hi\n");
        GeneratorSandbox bare = GeneratorSandbox.forCandidate(noShebang, IsolationSpec.local());
        assertThatThrownBy(() -> bare.run(new ProcessRequest(
                List.of(bare.sandboxDirectory().resolve("run.sh").toString()), 1_000)))
                .hasMessageContaining("shebang");
    }

    // ------------------------------------------------------------------ 审计

    @Test
    @DisplayName("审计落盘、按写入顺序、能按包回放；没身份记 anonymous；换行不拆行；没文件时为空")
    void 审计可回放() {
        Path file = parent.resolve("audit.tsv");
        Instant at = Instant.parse("2026-10-24T10:00:00Z");
        GenerationAudit audit = new GenerationAudit(file);

        audit.record(at, "ethan", GenerationAudit.Stage.GENERATED, "wordcount", "x", true);
        audit.record(at, null, GenerationAudit.Stage.SELF_TESTED, "wordcount", "第一行\n第二行", false);
        audit.record(at, "ethan", GenerationAudit.Stage.STATIC_REJECTED, "netprobe", "命中 NETWORK_CLIENT", false);

        List<GenerationAudit.Entry> entries = new GenerationAudit(file).all();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).stage()).isEqualTo(GenerationAudit.Stage.GENERATED);
        assertThat(entries.get(0).principal()).isEqualTo("ethan");
        assertThat(entries.get(0).allowed()).isTrue();
        assertThat(entries.get(1).principal()).isEqualTo("anonymous");
        assertThat(entries.get(1).detail()).contains("第二行");

        assertThat(audit.replay("wordcount")).hasSize(2);
        assertThat(audit.replay("netprobe").get(0).detail()).contains("NETWORK_CLIENT");
        assertThat(audit.replay("no-such-package")).isEmpty();
        assertThat(new GenerationAudit(parent.resolve("nope.tsv")).all()).isEmpty();
    }

    // ------------------------------------------------------------------ 装配

    @Test
    @DisplayName("配了暂存区才有生成器，清单里脚本包与 propose_script 并存；缺任一层就不装配")
    void 装配条件() throws IOException {
        Path packages = packagesDirectory("echo", "#!/bin/sh\necho hi\n");

        context(packages).run(context -> {
            assertThat(context).hasSingleBean(SkillGenerator.class);
            assertThat(toolNames(context)).contains("echo", SkillGeneratorToolCallback.TOOL_NAME);
        });

        scriptsOnly.withPropertyValues("claw4j.scripts.directory=" + packages).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SkillGenerator.class);
            assertThat(toolNames(context)).containsExactly("echo");
        });

        // 注册目标是脚本层的清单：没有它，签字会变成"没有效果却看起来成功"的动作
        scriptsOnly
                .withPropertyValues("claw4j.generator.staging-directory=" + parent.resolve("staging"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SkillGenerator.class);
                });
    }

    @Test
    @DisplayName("生成器这一层的权限必须显式登记为 draft_script，否则一律按未登记被拒")
    void 权限登记() throws IOException {
        Path packages = packagesDirectory("echo", "#!/bin/sh\necho hi\n");

        context(packages).run(context -> {
            boolean declared = context.getBeansOfType(ToolPermissionRegistry.class).values().stream()
                    .anyMatch(registry -> registry.permissionFor(SkillGeneratorToolCallback.TOOL_NAME)
                            .map(admin()::canUse)
                            .orElse(false));

            assertThat(declared).isTrue();
        });
    }

    @Test
    @DisplayName("没有携带身份的提交被拒，且不落进清单")
    void 无身份被拒() throws IOException {
        Path packages = packagesDirectory("echo", "#!/bin/sh\necho hi\n");

        context(packages).run(context -> {
            ToolCallback propose = Arrays.stream(toolCallbacks(context))
                    .filter(callback -> SkillGeneratorToolCallback.TOOL_NAME
                            .equals(callback.getToolDefinition().name()))
                    .findFirst().orElseThrow();

            String answer = propose.call(input("anon", "没身份的提交", "#!/bin/sh\necho hi\n"), null);

            assertThat(answer).contains("没有携带身份");
            assertThat(context.getBean(ScriptPackageRegistry.class).contains("anon")).isFalse();
        });
    }

    @Test
    @DisplayName("已注册的包被执行时审计里多一条 EXECUTED：跨层接线最易断在装配顺序上")
    void 执行也留痕() throws IOException {
        Path packages = packagesDirectory("echo", "#!/bin/sh\necho hi\n");

        context(packages).run(context -> {
            ToolCallback echo = Arrays.stream(toolCallbacks(context))
                    .filter(callback -> "echo".equals(callback.getToolDefinition().name()))
                    .findFirst().orElseThrow();

            echo.call("{}", new ToolContext(Map.of(ContextKeys.PRINCIPAL, admin())));

            GenerationAudit audit = context.getBean(GenerationAudit.class);
            assertThat(audit.replay("echo"))
                    .extracting(GenerationAudit.Entry::stage)
                    .contains(GenerationAudit.Stage.EXECUTED);
            assertThat(audit.replay("echo"))
                    .anySatisfy(entry -> assertThat(entry.principal()).isEqualTo("ethan"));
        });
    }

    @Test
    @DisplayName("清单里已有同名包时重复注册直接失败；后加的包同时出现在清单与权限表")
    void 清单注册() throws IOException {
        ScriptPackageRegistry registry = ScriptPackageRegistry.empty();
        Path root = Files.createDirectories(parent.resolve("dup"));

        assertThatThrownBy(() -> {
            registry.register(ScriptPackage.of("dup", root, "d"));
            registry.register(ScriptPackage.of("dup", root, "d"));
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("dup");

        ScriptPackageToolProvider provider = new ScriptPackageToolProvider(registry, new ScriptPackageRunner(List.of()));
        Path late = Files.createDirectories(parent.resolve("late"));
        Files.writeString(late.resolve("run.sh"), "#!/bin/sh\necho hi\n");

        registry.register(ScriptPackage.of("late", late, "d"));

        assertThat(provider.toolNames()).contains("late");
        assertThat(provider.permissions().get("late"))
                .isEqualTo(ToolPermission.of(ScriptPackageToolProvider.SCRIPT_ACTION));
    }

    // ------------------------------------------------------------------ 辅助

    private Fixture fixture(String name) {
        Path staging = parent.resolve("staging-" + name);
        Path reviews = parent.resolve("reviews-" + name);
        Path published = parent.resolve("published-" + name);
        ScriptPackageRegistry registry = ScriptPackageRegistry.empty();
        GenerationAudit audit = new GenerationAudit(parent.resolve("audit-" + name + ".tsv"));
        SkillGenerator generator = new SkillGenerator(staging, reviews, published, registry, audit,
                IsolationSpec.local(), CLOCK);
        return new Fixture(staging, reviews, registry, audit, generator,
                new ScriptPackageToolProvider(registry, new ScriptPackageRunner(List.of())));
    }

    private static String run(Fixture fixture, String name) {
        ScriptPackage pkg = fixture.registry.packages().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow();
        return new ScriptPackageRunner(List.of(pkg)).run(name, List.of());
    }

    private Path candidate(String name, String script) throws IOException {
        Path root = Files.createDirectories(parent.resolve(name));
        Path file = root.resolve("run.sh");
        Files.writeString(file, script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return root;
    }

    private ApplicationContextRunner context(Path packages) {
        return scriptsOnly
                .withPropertyValues("claw4j.scripts.directory=" + packages,
                        "claw4j.generator.staging-directory=" + parent.resolve("staging"),
                        "claw4j.generator.review-directory=" + parent.resolve("reviews"),
                        "claw4j.generator.audit-file=" + parent.resolve("audit.tsv"));
    }

    private Path packagesDirectory(String name, String script) throws IOException {
        Path root = Files.createDirectories(parent.resolve("packages").resolve(name));
        Path file = root.resolve(ScriptPackage.DEFAULT_ENTRYPOINT);
        Files.writeString(file, script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(root.resolve(ScriptPackageCatalog.MANIFEST), "description=一个回声脚本\n");
        return parent.resolve("packages");
    }

    private static String input(String name, String description, String script) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + description + "\",\"script\":"
                + "\"" + script.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
    }

    private static Principal admin() {
        return ETHAN;
    }

    private static List<String> toolNames(org.springframework.context.ApplicationContext context) {
        return context.getBeansOfType(ToolCallbackProvider.class).values().stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private static ToolCallback[] toolCallbacks(org.springframework.context.ApplicationContext context) {
        return context.getBeansOfType(ToolCallbackProvider.class).values().stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .toArray(ToolCallback[]::new);
    }

    private record Fixture(Path staging, Path reviews, ScriptPackageRegistry registry, GenerationAudit audit,
                           SkillGenerator generator, ScriptPackageToolProvider provider) {
    }
}
