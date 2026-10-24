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
     * Java 侧不自己起进程（ADR-0002）——<b>除 {@link #PROCESS_SPAWNER} 这一个被点名的类</b>。
     *
     * <p>1.x 的 {@code GenericSkill} 取一个 {@code .py}、把用户的话当参数传进去、
     * 经 {@code sh -c} 执行；{@code ShellUtils} 另有一条硬编码 root 的 SSH 通道。
     * 那条链路把宿主机的命令行交给了模型。新架构里起进程这件事收在一个类里。
     *
     * <p>为什么值得一条机器规则而不是靠 review 记：这条边界的失效方式是**悄悄的**。
     * 写一句 {@code new ProcessBuilder(...)} 能编译、能跑、能过所有人眼，
     * 只是从此"脚本层有超时、有目录锁、环境是干净的"这三句话全部失效，
     * 而表现出来的是"某个脚本跑得比别人快一点"。
     *
     * <p><b>这条规则在票 08 被收窄过一次</b>，收窄的理由记在票里与 ADR-0002 的修订记录：
     * 原先"任何地方都不许"逼着脚本层把一条命令行字符串交给社区包，
     * 而那条路是<b>经命令解释器执行的</b>——规则守住了"本项目不起进程"，
     * 却把"参数被解释器当成语法"这扇门留着，唯一挡它的是一处手写引号。
     * 于是改成自己起进程、传一个 argv 数组：那条门从此不存在。
     * 收窄的方向是"只有这一个类可以"，不是删掉规则——
     * 删掉之后"Java 侧不起进程"就没有任何机器证据了。
     *
     * <p>只查主代码：测试里为了验"沙箱外的路径被挡住"会自己造目录与软链，
     * 但那不需要起进程；真要起进程的地方就在沙箱测试里，而它起的是沙箱自己的后端。
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

    /** 全仓唯一被允许碰 {@code ProcessBuilder} 的类。加第二个之前先读票 08 里那段取舍。 */
    private static final String PROCESS_SPAWNER = "io.github.claw4j.tools.process.LocalProcessRunner";

    @ArchTest
    static final ArchRule java_side_must_not_spawn_processes =
            noClasses().that().resideInAPackage("io.github.claw4j..")
                    .and().doNotHaveFullyQualifiedName(PROCESS_SPAWNER)
                    .should().callMethodWhere(RUNTIME_EXEC)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                    .because("起进程只发生在 " + PROCESS_SPAWNER + "，别处不自己拼命令行（ADR-0002，票 08）");
}
