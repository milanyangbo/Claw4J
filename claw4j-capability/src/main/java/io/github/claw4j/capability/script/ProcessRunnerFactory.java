package io.github.claw4j.capability.script;

import io.github.claw4j.capability.process.ProcessRunner;

/**
 * 换沙箱的那条接缝：给一个脚本包配一个执行后端。
 *
 * <p>值得单独一个接口而不是直接 new，是因为脚本层的物理形态不是它唯一可能的形态：换容器、
 * 换远端执行器、换成测试里的记录器，改的都是这个实现（ADR-0003 的第 ② 类沙箱是另一个执行位置）。
 * 调用方 {@link ScriptPackageRunner} 只认 {@link ProcessRunner}，于是换形态只有一处可改。
 */
@FunctionalInterface
public interface ProcessRunnerFactory {

    /** 默认实现：{@link SandboxedProcessBackend}，工作目录锁在包目录上的受限子进程。 */
    ProcessRunnerFactory SANDBOXED = SandboxedProcessBackend::forPackage;

    /**
     * 为这个包建一个执行后端。
     *
     * @param pkg 要执行的包
     * @return 只认 argv 的执行后端
     */
    ProcessRunner forPackage(ScriptPackage pkg);
}
