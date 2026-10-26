package io.github.claw4j.capability;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 能力层的两条边界。
 *
 * <p>两条都是"代码照跑、日志干净、只是长错了地方"的那种失效：
 * 工具开始认识 Agent 之后，它就只能在一个已经装配好的 Agent 里被测试与复用；
 * 第二个起进程的地方出现之后，ADR-0002 那条"不经解释器"的保证就不再是全称的。
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
                    .should().accessClassesThat()
                    .haveFullyQualifiedName("java.lang.ProcessBuilder");
}
