package io.github.claw4j.tools.process;

/**
 * 起进程的那条接缝。
 *
 * <p>为什么值得单独一个接口而不是在沙箱里直接起进程：
 * "约束"（工作目录、环境、超时、身份）是策略，"起进程"是执行，
 * 两者换的时机完全不同——换沙箱形态换的是这里，改约束改的是 {@code SandboxPolicy}。
 * 混在一起的后果是：换形态时得把四条约束重新实现一遍，
 * 而"忘了实现其中一条"的表现是进程能跑、只是少锁了一样东西。
 *
 * <p><b>全仓库只有 {@link LocalProcessRunner} 实现它</b>，
 * 而且也只有那一个类碰得到 {@code ProcessBuilder}——这一条有架构测试盯着。
 */
public interface ProcessRunner {

    ProcessOutcome run(ProcessRequest request);
}
