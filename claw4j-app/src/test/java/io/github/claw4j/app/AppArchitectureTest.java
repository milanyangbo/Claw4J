package io.github.claw4j.app;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 跨模块的架构规则。只放在 app：别的模块各自只扫得见自己与它依赖的那些，
 * 而"整个 Java 侧都不许自己起进程"这条是要扫全部模块的（ADR-0010）。
 */
@AnalyzeClasses(packages = "io.github.claw4j", importOptions = ImportOption.DoNotIncludeTests.class)
class AppArchitectureTest {

    /**
     * Java 侧不自己起进程（ADR-0002）。
     *
     * <p>1.x 的 {@code GenericSkill} 取一个 {@code .py}、把用户的话当参数传进去、
     * 经 {@code sh -c} 执行；{@code ShellUtils} 另有一条硬编码 root 的 SSH 通道。
     * 那条链路把宿主机的命令行交给了模型。新架构里起进程这件事只发生在
     * 社区包的 {@code ExecBackend} 实现里——换沙箱换的是那个实现，本项目主代码不动。
     *
     * <p>为什么值得一条机器规则而不是靠 review 记：这条边界的失效方式是**悄悄的**。
     * 写一句 {@code new ProcessBuilder(...)} 能编译、能跑、能过所有人眼，
     * 只是从此"脚本层有超时、有目录锁、环境是干净的"这三句话全部失效，
     * 而表现出来的是"某个脚本跑得比别人快一点"。
     *
     * <p>只查主代码：测试里为了验"沙箱外的路径被挡住"会自己造目录与软链，
     * 但那不需要起进程；真要起进程的地方就在
     * {@code SandboxedExecBackendTest} 里，而它起的是沙箱自己的后端。
     */
    /**
     * 用 {@code callMethodWhere} 而不是 {@code callMethod(Runtime.class, "exec")}：
     * 后者那个重载的第三个参数是可变的形参类型，写成 {@code "exec"} 等于
     * "无参的 exec"，而 {@code exec} 没有一个无参重载——规则不会报错，
     * 只会永远匹配不上。这种"写了却从来没生效"的规则比没有规则更糟：
     * 它显示成绿，而它要挡的东西一直没人拦。
     */
    private static final DescribedPredicate<JavaMethodCall> RUNTIME_EXEC =
            DescribedPredicate.<JavaMethodCall>describe("调用 java.lang.Runtime 的 exec 起进程",
                    call -> "exec".equals(call.getTarget().getName())
                            && "java.lang.Runtime".equals(call.getTarget().getOwner().getName()));

    @ArchTest
    static final ArchRule java_side_must_not_spawn_processes =
            noClasses().that().resideInAPackage("io.github.claw4j..")
                    .should().callMethodWhere(RUNTIME_EXEC)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                    .because("起进程只发生在 ExecBackend 的实现里，Java 侧不自己拼命令行（ADR-0002）");
}
