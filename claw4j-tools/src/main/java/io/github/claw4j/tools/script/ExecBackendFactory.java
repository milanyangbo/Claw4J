package io.github.claw4j.tools.script;

import org.springaicommunity.agent.common.exec.ExecBackend;

/**
 * 换沙箱的那条接缝：给一个脚本包配一个执行后端。
 *
 * <p>为什么值得单独一个接口而不是直接 new：脚本层的物理形态是这张票定下来的
 * "应用容器内的受限子进程"，但它不是这一层唯一可能的形态——
 * 换容器、换远端执行器、换成测试里的记录器，改的都是这个实现
 * （ADR-0003 的第 ② 类沙箱是另一个执行位置，06 号票）。
 * 调用方 {@link ScriptPackageRunner} 只认 {@link ExecBackend}，
 * 于是换形态这件事在代码里只有一处可改。
 */
@FunctionalInterface
public interface ExecBackendFactory {

    /** 默认实现：{@link SandboxedExecBackend}，工作目录锁在包目录上的受限子进程。 */
    ExecBackendFactory SANDBOXED = SandboxedExecBackend::forPackage;

    ExecBackend forPackage(ScriptPackage pkg);
}
