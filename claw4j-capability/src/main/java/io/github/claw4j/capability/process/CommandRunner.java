package io.github.claw4j.capability.process;

import java.nio.file.Path;
import java.util.Map;

/**
 * {@code exec} 那侧的起进程接缝：一条命令、一个目录、一份环境、一个超时。
 *
 * <p>为什么不直接用 {@link ShellCommandRunner}：P17a 之后同一条命令有两个执行位置——本机的
 * {@code /bin/sh}，或另一台容器里的 {@code /bin/sh}。换位置的差别只在"这条命令交给谁"，判定
 * （白名单、审批、非 root、环境）与"在哪跑"无关，所以判定留在 {@code SandboxedExecBackend}，
 * 这里只换"交给谁"。
 *
 * <p>形状照 {@code ShellCommandRunner} 现有三个方法：{@link #run} 同步、{@link #start} 后台、
 * {@link #workingDirectory} 给判定与提示用。容器实现在 {@code sandbox} 包，它把这三个方法翻成
 * {@code docker run} 的 argv，再交给本机那条路——起进程的入口仍然只有两个。
 */
public interface CommandRunner {

    /**
     * 同步跑完一条命令。
     *
     * @param command       整条 shell 命令
     * @param directory     工作目录
     * @param environment   这条命令能看到的全部环境变量
     * @param timeoutMillis 墙上时钟上限
     * @return 执行结果；超时时状态是 {@link ProcessOutcome.Status#TIMED_OUT}
     */
    ProcessOutcome run(String command, Path directory, Map<String, String> environment, long timeoutMillis);

    /**
     * 起一个后台 shell，不设超时。
     *
     * @param command     整条 shell 命令
     * @param directory   工作目录
     * @param environment 这条命令能看到的全部环境变量
     * @return 后台句柄
     */
    ShellProcess start(String command, Path directory, Map<String, String> environment);

    /**
     * 命令在哪跑。判定与拒绝信息都读它。
     *
     * @return 工作目录
     */
    Path workingDirectory();
}
