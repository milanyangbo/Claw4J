package io.github.claw4j.tools.generator;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.tools.script.ScriptPackage;
import io.github.claw4j.tools.script.ScriptPackageCatalog;
import io.github.claw4j.tools.script.ScriptPackageRegistry;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Skill Generator：模型产出一个新脚本包的五步链路（ADR-0002 修订后的定案）。
 *
 * <pre>
 *   生成 → 静态检查 → 隔离沙箱自测 → 审计留痕 → 人审通过才注册
 * </pre>
 *
 * <p><b>五步的顺序就是安全边界的顺序</b>，不是流水线编排的偏好：
 * <ul>
 *   <li>静态检查在落盘<b>之前</b>——危险代码不该先被写下来再被删掉，
 *       那样磁盘上出现过它，而这件事没人会记录。
 *   <li>自测在沙箱里而不是在暂存区里——跑的是未审核代码，
 *       它不能改自己待审的那份内容（{@link GeneratorSandbox}）。
 *   <li>审计在这三步各自记一条，而不是最后补一条——
 *       最后补的那条只能写"发生了什么"，写不出"被挡下的是谁提交的"。
 *   <li>人审是最后一步，也是唯一没有代码替你做的那一步——
 *       {@code publishApproved()} 只读人写下的签字，没有"自动通过"的分支。
 * </ul>
 *
 * <p><b>未人审的脚本拿不到被调用的资格</b>，这条落在 {@link ScriptPackageRegistry} 上：
 * 工具清单由它现取，于是"待审"在模型那边就是"根本没有这个工具"。
 * 这不是靠"模型不去调它"维持的——它看不见，也就无从去调。
 */
public final class SkillGenerator {

    /** 候选包目录里的说明文件，与脚本层那份是同一个文件、同一个格式。 */
    public static final String MANIFEST = ScriptPackageCatalog.MANIFEST;

    /** 这次提交是谁提的、什么时候提的、脚本摘要是什么。 */
    public static final String PROPOSAL = "proposal.properties";

    /** 一个提交的形状。 */
    public record Request(String name, String description, String script, String entrypoint) {

        public Request(String name, String description, String script) {
            this(name, description, script, ScriptPackage.DEFAULT_ENTRYPOINT);
        }
    }

    /** 走到哪一步为止。 */
    public enum Status {
        /** 静态检查（或输入校验）没过：没有落盘，也无从自测。 */
        REJECTED,
        /** 落盘并自测过了，等人签字。 */
        PENDING_REVIEW,
        /** 人签了字，已经进工具清单。 */
        PUBLISHED
    }

    /**
     * 自测结果。
     *
     * @param exitCode 退出码
     * @param stdout 标准输出（截断后）
     * @param stderr 标准错误的<b>原话</b>
     * @param completed 是否在超时前跑完
     */
    public record SelfTestResult(int exitCode, String stdout, String stderr, boolean completed) {

        public boolean succeeded() {
            return completed && exitCode == 0;
        }

        /**
         * 给模型看的那一句。
         *
         * <p><b>失败时贴的是脚本的原话，不做包装。</b>
         * 与自纠错同一个道理：包装过的话里没有可改的信息。模型能修的只有"脚本说了什么"
         * ——{@code syntax error near unexpected token} 指得出行，
         * 而"自测未通过"指不出任何东西。
         */
        public String render() {
            if (succeeded()) {
                return "沙箱自测：退出码 0\n" + block(stdout);
            }
            String why = completed ? "退出码 " + exitCode : "超时，没有跑完";
            return "沙箱自测：失败（" + why + "）\n脚本自己的输出（原话，未包装）：\n" + block(stderr);
        }
    }

    /**
     * 一次提交的结果：五步每一步的产物都在里面。
     *
     * @param status 走到哪一步为止
     * @param staticCheck 第 2 步的产物
     * @param selfTest 第 3 步的产物；第 2 步没过时为空
     * @param sha256 落盘那版脚本的摘要，人审比对的就是它
     * @param candidate 暂存区里的候选目录；被拒时为空
     */
    public record Proposal(String packageName, Status status, ScriptStaticCheck.Report staticCheck,
                           Optional<SelfTestResult> selfTest, String sha256, Optional<Path> candidate) {

        /** 给模型的回执：五步一步一步写下来，让人和模型看到的是同一份东西。 */
        public String render() {
            StringBuilder text = new StringBuilder();
            text.append("1) 生成：").append(packageName);
            if (candidate.isPresent()) {
                text.append(" 已写入 ").append(candidate.get());
            }
            else {
                text.append(" 没有落盘");
            }
            text.append("\n2) ").append(staticCheck.render());
            if (selfTest.isPresent()) {
                text.append("\n3) ").append(selfTest.get().render());
            }
            else {
                text.append("\n3) 沙箱自测：没走到这一步");
            }
            return text.toString();
        }
    }

    private static final Set<PosixFilePermission> EXECUTABLE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);

    private final Path stagingDirectory;

    private final Path reviewDirectory;

    private final ScriptPackageRegistry registry;

    private final GenerationAudit audit;

    private final IsolationSpec spec;

    private final ScriptStaticCheck staticCheck;

    private final Clock clock;

    public SkillGenerator(Path stagingDirectory, Path reviewDirectory, ScriptPackageRegistry registry,
                          GenerationAudit audit, IsolationSpec spec, Clock clock) {
        this(stagingDirectory, reviewDirectory, registry, audit, spec, clock, new ScriptStaticCheck());
    }

    public SkillGenerator(Path stagingDirectory, Path reviewDirectory, ScriptPackageRegistry registry,
                          GenerationAudit audit, IsolationSpec spec, Clock clock,
                          ScriptStaticCheck staticCheck) {
        this.stagingDirectory = Objects.requireNonNull(stagingDirectory, "stagingDirectory");
        this.reviewDirectory = Objects.requireNonNull(reviewDirectory, "reviewDirectory");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.staticCheck = Objects.requireNonNull(staticCheck, "staticCheck");
    }

    /**
     * 走一遍五步。
     *
     * <p>返回的是一份回执而不是异常：模型要靠它改脚本，抛出去的话它拿到的
     * 只有一句"调用失败"，而那句话里没有可改的信息。
     * 只有"这一步根本不该发生"的情形（暂存区建不起来）才抛。
     */
    public Proposal propose(Principal who, Request request) {
        Objects.requireNonNull(request, "request");
        String principal = who == null ? null : who.userId();
        Instant now = clock.instant();

        Optional<ScriptStaticCheck.Finding> invalid = validate(request);
        if (invalid.isPresent()) {
            ScriptStaticCheck.Report rejected = new ScriptStaticCheck.Report(false,
                    ScriptLanguage.ofEntrypoint(entrypointOf(request)), List.of(invalid.get()));
            audit.record(now, principal, GenerationAudit.Stage.STATIC_REJECTED, safeName(request),
                    invalid.get().why(), false);
            return new Proposal(safeName(request), Status.REJECTED, rejected, Optional.empty(), "",
                    Optional.empty());
        }

        ScriptStaticCheck.Report report = staticCheck.check(entrypointOf(request), request.script());
        if (!report.accepted()) {
            audit.record(now, principal, GenerationAudit.Stage.STATIC_REJECTED, safeName(request),
                    report.render(), false);
            return new Proposal(safeName(request), Status.REJECTED, report, Optional.empty(), "", Optional.empty());
        }

        Path candidate = writeCandidate(request, principal, now);
        String sha256 = ReviewGate.digest(request.script());
        audit.record(now, principal, GenerationAudit.Stage.GENERATED, request.name(),
                "写入 " + candidate + "，sha256=" + sha256, true);

        SelfTestResult selfTest = selfTest(candidate, request);
        audit.record(clock.instant(), principal, GenerationAudit.Stage.SELF_TESTED, request.name(),
                selfTest.succeeded() ? "退出码 0，输出 " + selfTest.stdout() : "失败：" + selfTest.stderr(),
                selfTest.succeeded());

        // 第五步：本次提交如果已经有人签过字（同一版脚本重提），这里就会把它上线。
        List<String> published = publishApproved();
        Status status = published.contains(request.name()) ? Status.PUBLISHED : Status.PENDING_REVIEW;
        return new Proposal(request.name(), status, report, Optional.of(selfTest), sha256, Optional.of(candidate));
    }

    /**
     * 第五步：把<b>已经有人签过字的</b>候选包放进工具清单。
     *
     * <p>它只读签字，不做任何"看起来可以放行"的推断：没有标记、写得不合格、
     * 摘要对不上（脚本在签字之后被改过）——三种情形都是不放行。
     *
     * <p>为什么是一个独立的方法而不是塞在 {@link #propose} 里：
     * 人签字通常发生在提交之后，链路需要一个"再看一眼有没有人签过"的动作，
     * 而那个动作不该依赖"再生成一次"。
     *
     * @return 这次真正上线的包名
     */
    public List<String> publishApproved() {
        List<String> published = new ArrayList<>();
        if (!Files.isDirectory(stagingDirectory)) {
            return published;
        }
        for (Path candidate : candidateDirectories()) {
            String name = candidate.getFileName().toString();
            if (registry.contains(name)) {
                continue;
            }
            Optional<ReviewGate.Decision> decision = new ReviewGate().read(reviewDirectory, name);
            if (decision.isEmpty()) {
                continue;
            }
            String script = readEntrypoint(candidate);
            if (script == null) {
                continue;
            }
            if (!new ReviewGate().matches(decision.get(), script)) {
                audit.record(clock.instant(), null, GenerationAudit.Stage.APPROVED, name,
                        "签字与当前脚本不一致：审过的是 " + decision.get().sha256() + "，现在是 "
                                + ReviewGate.digest(script) + "，这次签字自动作废", false);
                continue;
            }
            ScriptPackage pkg = ScriptPackage.of(name, candidate, entrypointNameOf(candidate),
                    descriptionOf(candidate), ScriptPackage.DEFAULT_TIMEOUT_MILLIS);
            registry.register(pkg);
            audit.record(clock.instant(), decision.get().by(), GenerationAudit.Stage.APPROVED, name,
                    "由 " + decision.get().by() + " 于 " + decision.get().at() + " 签字通过", true);
            audit.record(clock.instant(), decision.get().by(), GenerationAudit.Stage.PUBLISHED, name,
                    "已注册为脚本包，目录 " + candidate, true);
            published.add(name);
        }
        return List.copyOf(published);
    }

    /** 暂存区里现在有哪些候选包。 */
    public List<Path> candidates() {
        return candidateDirectories();
    }

    public Path stagingDirectory() {
        return stagingDirectory;
    }

    public Path reviewDirectory() {
        return reviewDirectory;
    }

    // ------------------------------------------------------------------ 五步的实现

    private SelfTestResult selfTest(Path candidate, Request request) {
        GeneratorSandbox sandbox = GeneratorSandbox.forCandidate(candidate, spec);
        try {
            ExecResult result = sandbox.run(new ExecSpec(quote("./" + entrypointOf(request)),
                    spec.timeoutMillis(), java.util.Map.of()));
            return new SelfTestResult(result.exitCode(), clip(result.stdout()), clip(result.stderr()),
                    result.status() == ExecResult.Status.COMPLETED);
        }
        catch (RuntimeException failure) {
            // 自测自己出问题（沙箱起不来、超时被杀）也要变成"脚本没跑出来"，
            // 而不是把整通对话炸掉：模型要知道的是"这份脚本这次没能验证"。
            return new SelfTestResult(-1, "", failure.getMessage(), false);
        }
    }

    private Path writeCandidate(Request request, String principal, Instant now) {
        try {
            Path candidate = Files.createDirectories(stagingDirectory.resolve(request.name()));
            Path script = candidate.resolve(entrypointOf(request));
            Files.writeString(script, request.script(), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(script, EXECUTABLE);
            Properties manifest = new Properties();
            manifest.setProperty("description", request.description());
            manifest.setProperty("entrypoint", entrypointOf(request));
            writeProperties(candidate.resolve(MANIFEST), manifest);
            Properties proposal = new Properties();
            proposal.setProperty("by", principal == null ? "anonymous" : principal);
            proposal.setProperty("at", now.toString());
            proposal.setProperty("sha256", ReviewGate.digest(request.script()));
            writeProperties(candidate.resolve(PROPOSAL), proposal);
            return candidate;
        }
        catch (IOException failure) {
            throw new UncheckedIOException("写候选包 " + request.name() + " 失败", failure);
        }
    }

    private List<Path> candidateDirectories() {
        try (Stream<Path> children = Files.list(stagingDirectory)) {
            return children.filter(Files::isDirectory).sorted().toList();
        }
        catch (IOException failure) {
            throw new UncheckedIOException("读暂存区 " + stagingDirectory + " 失败", failure);
        }
    }

    private static String readEntrypoint(Path candidate) {
        String entrypoint = entrypointNameOf(candidate);
        Path script = candidate.resolve(entrypoint);
        if (!Files.isRegularFile(script)) {
            return null;
        }
        try {
            return Files.readString(script, StandardCharsets.UTF_8);
        }
        catch (IOException failure) {
            throw new UncheckedIOException("读候选脚本 " + script + " 失败", failure);
        }
    }

    /** 入口名从候选目录的清单里读：人审与目标必须是同一份声明。 */
    private static String entrypointNameOf(Path candidate) {
        Path manifest = candidate.resolve(MANIFEST);
        if (Files.isRegularFile(manifest)) {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            catch (IOException failure) {
                throw new UncheckedIOException("读 " + manifest + " 失败", failure);
            }
            return properties.getProperty("entrypoint", ScriptPackage.DEFAULT_ENTRYPOINT);
        }
        return ScriptPackage.DEFAULT_ENTRYPOINT;
    }

    private static String descriptionOf(Path candidate) {
        Path manifest = candidate.resolve(MANIFEST);
        Properties properties = new Properties();
        if (Files.isRegularFile(manifest)) {
            try (var reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            catch (IOException failure) {
                throw new UncheckedIOException("读 " + manifest + " 失败", failure);
            }
        }
        String description = properties.getProperty("description");
        return (description == null || description.isBlank()) ? "（候选包未写描述）" : description;
    }

    private static void writeProperties(Path file, Properties properties) throws IOException {
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }
    }

    private static String entrypointOf(Request request) {
        String entrypoint = request.entrypoint() == null || request.entrypoint().isBlank()
                ? ScriptPackage.DEFAULT_ENTRYPOINT
                : request.entrypoint();
        // 入口只能是包内的一个文件名：含路径分隔符的话就指到别处去了。
        return Path.of(entrypoint).getFileName().toString();
    }

    /**
     * 输入本身合不合规格：包名会原样发给模型供应商当函数名，
     * 描述是模型判断"什么时候该调它"的唯一依据。
     *
     * <p>这两项不合格不受静态检查管——静态检查看的是脚本正文，
     * 而这里不合格时连脚本都不用看。
     */
    private static Optional<ScriptStaticCheck.Finding> validate(Request request) {
        String name = request.name();
        if (name == null || !ScriptPackage.isValidName(name)) {
            return Optional.of(new ScriptStaticCheck.Finding("BAD_NAME", 0, String.valueOf(name),
                    "包名必须是 1-64 位的字母、数字、下划线或连字符：它会原样发给模型供应商当函数名，"
                            + "含空格或中文的请求会被 API 拒"));
        }
        if (request.description() == null || request.description().isBlank()) {
            return Optional.of(new ScriptStaticCheck.Finding("NO_DESCRIPTION", 0, "",
                    "缺 description：模型靠这句描述决定什么时候调它，没有描述的包永远不会被调用，而且不会报错"));
        }
        if (request.script() == null || request.script().isBlank()) {
            return Optional.of(new ScriptStaticCheck.Finding("EMPTY_SCRIPT", 0, "",
                    "脚本是空的：没有可检查、也没有可自测的东西"));
        }
        return Optional.empty();
    }

    private static String safeName(Request request) {
        return request.name() == null ? "(null)" : request.name();
    }

    private String clip(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        if (stripped.length() <= spec.maxOutputChars()) {
            return stripped;
        }
        return stripped.substring(0, spec.maxOutputChars()) + "\n…（输出超过 " + spec.maxOutputChars()
                + " 字符，已截断）";
    }

    /** 单引号包起来，内部的单引号按 POSIX 的写法断开再接上。 */
    private static String quote(String raw) {
        return "'" + raw.replace("'", "'\\''") + "'";
    }

    private static String block(String text) {
        return text == null || text.isBlank() ? "（没有输出）" : text;
    }
}
