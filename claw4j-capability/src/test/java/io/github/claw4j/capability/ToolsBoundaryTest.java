package io.github.claw4j.capability;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 能力层的两条边界：不认 Agent 与 Channel、不起进程。
 *
 * <p>两条都是"代码照跑、日志干净、只是长错了地方"的那种失效：工具开始认识 Agent 之后，它就只能
 * 在一个已经装配好的 Agent 里被测试与复用；自己起进程的地方多出一个之后，ADR-0002 那条保证
 * 就不再是全称的。
 *
 * <p><b>2026-11-18 起这条规则零例外。</b>脚本包的 argv 执行器随脚本层一起删了（脚本退化成技能，
 * 由模型用 {@code Bash} 去跑），{@code exec} 的宿主与容器后端都在库的 jar 里
 * （{@code LocalExecBackend} / {@code DockerCliExecBackend}）。
 */
@AnalyzeClasses(packages = "io.github.claw4j.capability", importOptions = ImportOption.DoNotIncludeTests.class)
class ToolsBoundaryTest {

    @ArchTest
    static final ArchRule capabilityKnowsNeitherAgentNorChannel =
            noClasses().that().resideInAPackage("io.github.claw4j.capability..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("io.github.claw4j.agent..", "io.github.claw4j.channel..");

    @ArchTest
    static final ArchRule nobodyStartsProcesses =
            noClasses().that().resideInAPackage("io.github.claw4j.capability..")
                    .should().accessClassesThat()
                    .haveFullyQualifiedName("java.lang.ProcessBuilder");
}
