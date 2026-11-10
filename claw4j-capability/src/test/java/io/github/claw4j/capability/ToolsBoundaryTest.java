package io.github.claw4j.capability;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 能力层的两条边界：不认 Agent 与 Channel，起进程只经点名的两个类。
 *
 * <p>两条都是"代码照跑、日志干净、只是长错了地方"的那种失效：工具开始认识 Agent 之后，它就只能
 * 在一个已经装配好的 Agent 里被测试与复用；起进程的地方多出第三个之后，ADR-0002 那条保证
 * 就不再是全称的。
 *
 * <p>现在被点名的有两个：{@code LocalProcessRunner}（argv，脚本包走这条）与
 * {@code ShellCommandRunner}（{@code /bin/sh -c}，{@code exec} 走这条）。区别在"这条命令行是谁给的"
 * （票 01）。多出第三个时这条规则会红，那是要一次裁决的信号。
 */
@AnalyzeClasses(packages = "io.github.claw4j.capability", importOptions = ImportOption.DoNotIncludeTests.class)
class ToolsBoundaryTest {

    @ArchTest
    static final ArchRule capabilityKnowsNeitherAgentNorChannel =
            noClasses().that().resideInAPackage("io.github.claw4j.capability..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("io.github.claw4j.agent..", "io.github.claw4j.channel..");

    @ArchTest
    static final ArchRule onlyLocalProcessRunnerStartsProcesses =
            noClasses().that()
                    .doNotHaveFullyQualifiedName("io.github.claw4j.capability.process.LocalProcessRunner")
                    .and().doNotHaveFullyQualifiedName("io.github.claw4j.capability.process.ShellCommandRunner")
                    .should().accessClassesThat()
                    .haveFullyQualifiedName("java.lang.ProcessBuilder");
}
