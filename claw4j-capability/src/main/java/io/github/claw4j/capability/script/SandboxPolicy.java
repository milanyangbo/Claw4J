package io.github.claw4j.capability.script;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 脚本层的四条约束：路径、超时、非 root 账号、环境变量白名单。
 *
 * <p>四条是 ADR-0003 里"① 应用容器内执行"的落地，那里把它们记为对官方建议做法的采用
 * （限制工作目录、超时、非 root 用户）。这个类做的是把建议变成会被测试拦住的代码。
 *
 * <p>物理形态是应用容器内的一个子进程，不是另一台容器。判据是"能不能在 CI 里跑起来"：
 * runner 上没有 Docker 守护进程，依赖容器的沙箱在 CI 上就是一串红灯，而没有测试的边界
 * 不能写进 README 当承诺。给不了的保证（脚本体内自己读绝对路径、自己连网）写在
 * {@link ScriptPackageRunner} 的类注释里。
 */
public final class SandboxPolicy {

    /**
     * 单次执行的墙上时钟上限。脚本层不接长任务：一次"问一句话、拿一个结果"的调用跑满 30 秒，
     * 已经说明它该换执行位置（ADR-0003 的第 ② 类沙箱）。
     */
    public static final long MAX_TIMEOUT_MILLIS = 30_000;

    /** 以这个账号起子进程一律拒绝。 */
    public static final String ROOT_ACCOUNT = "root";

    private static final SandboxPolicy DEFAULTS = new SandboxPolicy();

    private SandboxPolicy() {
    }

    /**
     * 默认策略实例；四条约束都是固定值，没有可配的字段。
     *
     * @return 唯一实例
     */
    public static SandboxPolicy defaults() {
        return DEFAULTS;
    }

    /**
     * 子进程能看到的全部环境变量：只有 {@code PATH}。
     *
     * <p>选白名单而不是"继承 JVM 的、去掉几个敏感的"：黑名单的失效方式是静默的，加一个
     * {@code XXX_API_KEY} 的人不会想到回这里补一条；白名单的失效方式是脚本找不到某个命令，
     * 一次立刻可见的失败。两种失效里只有后者会被人发现。
     *
     * <p>{@code PATH} 留下是因为它装的是目录名而不是凭据；不给它，脚本连 {@code cat} 都找不到。
     *
     * @return 只含 {@code PATH} 的环境，取不到时为不可变空表
     */
    public Map<String, String> environment() {
        String path = System.getenv("PATH");
        return path == null || path.isBlank() ? Map.of() : Map.of("PATH", path);
    }

    /**
     * 超时必须在上限内，超过就拒绝执行，而不是"跑着看"。
     *
     * <p>不夹取（clamp）而拒绝：夹取之后"申请了 10 分钟"与"实际只跑了 30 秒"在配置里
     * 看起来是同一件事，而这两个数字不一致正是要暴露的配置错误。
     *
     * @param timeoutMillis 请求的超时，毫秒
     * @return 原样返回 {@code timeoutMillis}
     * @throws SandboxViolationException 非正数，或超过 {@link #MAX_TIMEOUT_MILLIS}
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
     * 非 root：以 root 身份起子进程一律拒绝。
     *
     * <p>这里只能拒绝，不能降权：降权要在 fork 之前 setuid，纯 Java 拿不到那条路，
     * 再起一个外部命令来降权则等于把宿主机命令行交给别人。容器镜像里的 {@code USER}
     * 才是真正保证非 root 的那一层，这一条挡的是"应用被跑成 root 时脚本层跟着变成 root"。
     *
     * <p>判据取 {@code user.name} 而不是 uid：uid 没有跨平台的纯 Java 读法，而这个项目要在
     * macOS 与 Linux 的 CI 上跑同一套测试。
     *
     * @param accountName 当前执行账号
     * @throws SandboxViolationException 账号取不到，或账号就是 root
     */
    public void requireNonRoot(String accountName) {
        if (accountName == null || accountName.isBlank()) {
            throw new SandboxViolationException("取不到当前执行账号，按最坏情况拒绝执行脚本");
        }
        if (ROOT_ACCOUNT.equals(accountName)) {
            throw new SandboxViolationException("拒绝以 root 身份执行脚本：脚本层必须跑在非 root 账号下（ADR-0003）");
        }
    }

    /*
     * "调用方不能往执行环境里塞环境变量"这条约束（票 08）由类型保证：{@code ProcessRequest}
     * 只有 argv 与超时两项，环境变量只在起进程时由沙箱给出一次，于是"塞一个变量进来"没有入口。
     *
     * 不靠运行时再判一遍：那要求请求带着一个 env 字段，于是每次执行都得重判，还会被漏掉。
     */

    /**
     * 把一个相对路径解析成沙箱内的绝对路径，越界即拒绝。
     *
     * <p>这里是"碰不到包目录之外的东西"这条边界在代码里的落点。三层能力对 Agent 呈现为一回事，
     * 模型给的参数是唯一能越过这条线的东西：脚本本体写死在包里，命令由包清单定死。
     *
     * <p>两道检查都要有：{@code normalize()} 挡 {@code ..} 这种字面量，{@code toRealPath()}
     * 挡符号链接。只做前者时，包目录里一个指向 {@code /etc} 的软链就能整目录穿出去，
     * 而 {@code ..} 一个字都没出现。
     *
     * @param root 包目录，沙箱的边界
     * @param relative 待解析的相对路径
     * @return 沙箱内的绝对路径，可能尚不存在
     * @throws SandboxViolationException 路径为空、不是合法路径、是绝对路径，或解析后落在包目录之外
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
