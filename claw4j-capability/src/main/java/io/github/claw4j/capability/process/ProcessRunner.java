package io.github.claw4j.capability.process;

/**
 * 起进程的接缝：按 argv 起一个进程，收回两路输出。
 *
 * <p>单独一个接口而不是在沙箱里直接起进程，是因为"约束"（工作目录、环境、超时、身份）是策略，
 * "起进程"是执行，两者换的时机不同：换沙箱形态换这里，改约束改 {@code SandboxPolicy}。
 * 混在一起的话，换形态时要重写四条约束，而漏掉一条的表现是进程能跑、只是少锁了一样东西。
 *
 * <p>实现有三个：{@link LocalProcessRunner}、只做转发的 {@code SandboxedProcessBackend}，
 * 以及生成器那层的 {@code GeneratorSandbox}。只有 {@link LocalProcessRunner} 碰得到
 * {@code ProcessBuilder}，这一条有架构测试盯着。
 */
public interface ProcessRunner {

    /**
     * 执行一次请求，同步等到结束或超时。
     *
     * @param request argv 与超时
     * @return 进程的执行结果
     */
    ProcessOutcome run(ProcessRequest request);
}
