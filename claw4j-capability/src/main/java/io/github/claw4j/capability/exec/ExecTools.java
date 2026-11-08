package io.github.claw4j.capability.exec;

import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ShellCommandRunner;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code exec}：一条 shell 命令，返回退出码与两路输出（票 01，照 OpenClaw 的 {@code exec}）。
 *
 * <p><b>它为什么存在</b>：模型要跑代码的唯一路径曾经是脚本包——入口脚本由包清单定死、参数走 argv。
 * 那条路对"已固化的动作"是对的，但"看一下这个目录""跑一下这段命令"做不了。
 * OpenClaw 的答案是一个通用 shell 工具，做得很正式：命令字符串、workdir、env、超时、
 * 四档权限模式、命令白名单、人批。这一版照它的形态，边界收在我们自己的沙箱里。
 *
 * <p><b>它不是 {@link io.github.claw4j.capability.script.ScriptPackageRunner} 的替代</b>：
 * 脚本包仍是"参数是数据"的那条路。两条路的分界线是**这条字符串是谁给的**——
 * 包清单给的是 argv，模型给的是命令。
 *
 * <p><b>判定顺序固定</b>：非 root → 白名单 → 工作目录 → 环境 → 超时 → 起进程。
 * 顺序不是随意的：先判最便宜的、再判要碰文件系统的，最后才是起进程——任何一步失败都不该留下副作用。
 */
public class ExecTools {

    /** 单路输出上限。超过就截，附一句怎么缩小范围——不截的话一条 `cat` 就能把上下文吃满。 */
    static final int MAX_OUTPUT_CHARS = 8_000;

    private static final String DESCRIPTION = "执行一条 shell 命令，返回退出码与输出。"
            + "做那些没有专用工具的事：看目录、数文件、跑构建、看进程。"
            + "工作目录默认在工作区内，只能给相对路径；需要管道或重定向时直接写在命令里。"
            + "拿不到结果之前不要假设它成功了——先看退出码。";

    private final ExecProperties properties;

    private final ShellCommandRunner runner;

    private final Path workspace;

    private final Map<String, String> inheritedEnvironment;

    private final String accountName;

    public ExecTools(ExecProperties properties, ShellCommandRunner runner) {
        this(properties, runner, System.getenv(), System.getProperty("user.name", ""));
    }

    /**
     * @param inheritedEnvironment 继承给命令的环境；测试传一份固定值，免得断言依赖跑测试的那台机器
     * @param accountName          当前执行账号；测试传 {@code root} 验那条拒绝
     */
    public ExecTools(ExecProperties properties, ShellCommandRunner runner,
                     Map<String, String> inheritedEnvironment, String accountName) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.workspace = properties.workspace().toAbsolutePath().normalize();
        this.inheritedEnvironment = Map.copyOf(Objects.requireNonNull(inheritedEnvironment, "inheritedEnvironment"));
        this.accountName = accountName;
    }

    @Tool(name = "exec", description = DESCRIPTION)
    public String exec(@ToolParam(description = "要执行的 shell 命令，例如 ls -la 或 git status") String command,
                       @ToolParam(description = "相对工作区的目录，不填就是工作区根", required = false) String workdir,
                       @ToolParam(description = "额外的环境变量，例如 {\"FOO\":\"bar\"}", required = false)
                       Map<String, String> env,
                       @ToolParam(description = "超时毫秒，不填用默认值", required = false) Long timeoutMillis) {
        if (command == null || command.isBlank()) {
            return "命令是空的，这次没有执行。";
        }
        try {
            ExecPolicy.requireNonRoot(accountName);
            if (!whitelisted(command)) {
                return "这条命令没有执行：" + (properties.mode() == ExecProperties.Mode.ALLOWLIST
                        ? "allowlist 模式下只跑白名单里的命令，这条不在里面（白名单：" + allowText() + "）。"
                        : "命令里出现 shell 控制符或不在白名单里（白名单：" + allowText() + "）。");
            }
            Path directory = ExecPolicy.resolveInside(workspace, workdir);
            Map<String, String> environment = ExecPolicy.environment(inheritedEnvironment, env);
            long millis = ExecPolicy.timeoutMillis(
                    timeoutMillis == null ? null : Duration.ofMillis(timeoutMillis),
                    properties.defaultTimeout(), properties.maxTimeout());
            return render(runner.run(command, directory, environment, millis));
        }
        catch (ExecViolationException violation) {
            return "这条命令没有执行：" + violation.getMessage() + "。";
        }
    }

    /** {@code full} 不判；另两档要过白名单（{@code ask} 的"批"由鉴权 Advisor 按权限登记来管）。 */
    private boolean whitelisted(String command) {
        return properties.mode() == ExecProperties.Mode.FULL || ExecPolicy.permits(properties.allow(), command);
    }

    private String allowText() {
        List<String> allow = properties.allow();
        return allow.isEmpty() ? "空，等于一条都不放行" : String.join("、", allow);
    }

    /**
     * 两路输出分开给：混成一路之后"退出码非零时该看哪一句"就说不清了。
     * 首行固定是 {@code [exit N]}，超时时是 {@code [timeout]}——模型先看这一行再读内容。
     */
    private static String render(ProcessOutcome outcome) {
        StringBuilder out = new StringBuilder();
        if (outcome.status() == ProcessOutcome.Status.TIMED_OUT) {
            out.append("[timeout] 到时限还没结束，进程已被终止。可以把命令拆小，或者去掉不需要的部分再跑一次。");
        }
        else {
            out.append("[exit ").append(outcome.exitCode()).append(']');
        }
        append(out, outcome.stdout(), "");
        append(out, outcome.stderr(), "stderr:");
        return out.toString();
    }

    private static void append(StringBuilder out, String text, String label) {
        if (text == null || text.isBlank()) {
            return;
        }
        out.append('\n');
        if (!label.isEmpty()) {
            out.append(label).append('\n');
        }
        if (text.length() <= MAX_OUTPUT_CHARS) {
            out.append(text.stripTrailing());
            return;
        }
        out.append(text, 0, MAX_OUTPUT_CHARS).append("\n…（输出过长，只保留前 ")
                .append(MAX_OUTPUT_CHARS).append(" 字。缩小范围再看：加 head、grep，或者换个目录）");
    }
}
