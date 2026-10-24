package io.github.claw4j.tools.script;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 脚本层执行环境的约束，四条，各自都能单独被违反（ADR-0003 的"① 应用容器内执行"）。
 *
 * <p><b>这四条不是我们自己发明的</b>：ADR-0003 引的就是官方给出的建议做法
 * （限制工作目录、超时、非 root 用户），这张票只是把它们变成会被测试拦住的代码。
 *
 * <p><b>物理形态是"应用容器内的一个子进程"，不是另一台容器</b>。判据是
 * "能不能在 CI 里跑起来"：跑不起来的沙箱等于没有测试，而没有测试的边界不能写进
 * README 当承诺。CI 的 runner 上没有 Docker 守护进程（数据库那一类是靠
 * {@code CLAW4J_POSTGRES_IMAGE} 指定的镜像），依赖容器的沙箱在 CI 上就是一串红灯。
 * 于是这里能给的保证只有这四条，给不了的（脚本体内自己去读绝对路径、自己去连网）
 * 写在 {@code ScriptPackageRunner} 的类注释里，不在 README 里充数。
 */
public final class SandboxPolicy {

    /**
     * 单次执行的墙上时钟上限。脚本层不接长任务：一个"问一句话、拿一个结果"的能力
     * 跑满 30 秒已经说明它该换个执行位置（ADR-0003 的第 ② 类沙箱，06 号票）。
     */
    public static final long MAX_TIMEOUT_MILLIS = 30_000;

    /** 以这个账号起子进程一律拒绝。 */
    public static final String ROOT_ACCOUNT = "root";

    private static final SandboxPolicy DEFAULTS = new SandboxPolicy();

    private SandboxPolicy() {
    }

    public static SandboxPolicy defaults() {
        return DEFAULTS;
    }

    /**
     * 子进程能看到的全部环境变量：只有 {@code PATH}。
     *
     * <p>为什么是"只有 PATH"而不是"继承 JVM 的、去掉几个敏感的"：
     * 去掉几个敏感的那条路是黑名单，而黑名单的失效方式是静默的——
     * 加一个新的 {@code XXX_API_KEY} 的人不会想到要回这里补一条。
     * 反过来白名单的失效方式是"脚本找不到某个命令"，那是一次立刻可见的失败。
     * 两种失效里只有后者会被人发现，所以宁可要后者。
     *
     * <p>{@code PATH} 之所以能留下：它装的是目录名，不是凭据。
     * 不给它的后果是脚本连 {@code cat} 都找不到，那是把"可用"也一起砍掉了。
     */
    public Map<String, String> environment() {
        String path = System.getenv("PATH");
        return path == null || path.isBlank() ? Map.of() : Map.of("PATH", path);
    }

    /**
     * 超时必须在上限内，超过就拒绝执行而不是"跑着看"。
     *
     * <p>不夹取（clamp）而拒绝：夹取的话"我申请了 10 分钟"和"实际只跑了 30 秒"
     * 在配置里看起来是同一件事，而这两个数字不一致正是要暴露出来的配置错误。
     */
    public long requireTimeoutWithinCap(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new SandboxViolationException("超时必须是正数，拿到的是 " + timeoutMillis + "ms");
        }
        if (timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new SandboxViolationException("超时 " + timeoutMillis + "ms 超过上限 " + MAX_TIMEOUT_MILLIS
                    + "ms：脚本层不接长任务，长任务该走另一个执行位置（ADR-0003）");
        }
        return timeoutMillis;
    }

    /**
     * 非 root：以 root 身份起子进程这件事直接拒绝。
     *
     * <p>这里能做的只有"拒绝"而不是"降权"：降权要在 fork 之前 setuid，
     * 纯 Java 拿不到那条路（除了再起一个外部命令，那就又把宿主机的命令行交出去了）。
     * 容器镜像里的 {@code USER} 才是真正保证非 root 的那一层，
     * 这一条的作用是"就算有人把应用跑成了 root，脚本层也不会跟着变成 root 执行"。
     *
     * <p>判据取 {@code user.name} 而不是 uid：uid 没有跨平台的纯 Java 读法，
     * 而这个项目要在 macOS 与 Linux 的 CI 上跑同一套测试。
     */
    public void requireNonRoot(String accountName) {
        if (accountName == null || accountName.isBlank()) {
            throw new SandboxViolationException("取不到当前执行账号，按最坏情况拒绝执行脚本");
        }
        if (ROOT_ACCOUNT.equals(accountName)) {
            throw new SandboxViolationException("拒绝以 root 身份执行脚本：脚本层必须跑在非 root 账号下（ADR-0003）");
        }
    }

    /**
     * "调用方不能往执行环境里塞环境变量"这条约束，票 08 之后由<b>类型</b>保证：
     * {@code ProcessRequest} 只有 argv 与超时两项，环境变量只在起进程时被给出一次
     * （由沙箱给，不由调用方给），于是"塞一个变量进来"没有入口。
     *
     * <p>原先这里是一条运行时检查（{@code requireNoInjectedEnvironment}），
     * 那版接缝上请求里带着一个 env 字段，于是每次执行都得再判一遍。
     * 结构上的保证比运行时再判一次更靠得住：它不会被人忘掉调用。
     * 盯住这件事的现在是 {@code LocalProcessRunnerTest} 里那条"请求里没有环境变量这一项"。
     */

    /**
     * 把一个相对路径解析成沙箱内的绝对路径，越界即拒绝。
     *
     * <p><b>这是"脚本想碰沙箱目录之外的东西被挡住"那条边界在代码里的位置。</b>
     * 三层能力对 Agent 呈现为一回事，模型给的参数是唯一能越过这条线的东西——
     * 脚本本体是包里写死的，命令是包清单定死的，能变的只有参数。
     *
     * <p>两道检查都要有：{@code normalize()} 挡的是 {@code ..} 这种字面量，
     * {@code toRealPath()} 挡的是符号链接。只做前者时，包目录里放一个
     * 指向 {@code /etc} 的软链就能整目录穿出去，而 {@code ..} 一个字都没出现。
     */
    public Path resolveInside(Path root, String relative) {
        Objects.requireNonNull(root, "root");
        if (relative == null || relative.isBlank()) {
            throw new SandboxViolationException("路径参数不能为空");
        }
        Path candidate;
        try {
            candidate = Path.of(relative);
        }
        catch (InvalidPathException failure) {
            throw new SandboxViolationException("路径参数不是合法路径：" + relative, failure);
        }
        if (candidate.isAbsolute()) {
            throw new SandboxViolationException("路径参数必须是相对路径，拿到的是 " + relative);
        }
        Path rootNormalized = root.toAbsolutePath().normalize();
        Path resolved = rootNormalized.resolve(candidate).normalize();
        if (!resolved.startsWith(rootNormalized)) {
            throw new SandboxViolationException("路径 " + relative + " 越出了脚本包自己的目录 " + rootNormalized);
        }
        if (!Files.exists(resolved)) {
            return resolved;
        }
        try {
            if (!resolved.toRealPath().startsWith(rootNormalized.toRealPath())) {
                throw new SandboxViolationException("路径 " + relative + " 经符号链接指到了沙箱目录之外");
            }
        }
        catch (IOException failure) {
            throw new SandboxViolationException("解析路径 " + relative + " 失败", failure);
        }
        return resolved;
    }
}
