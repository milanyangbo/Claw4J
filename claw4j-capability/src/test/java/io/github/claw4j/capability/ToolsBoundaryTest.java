package io.github.claw4j.capability;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 工具层的边界（ADR-0010）：不认识 Agent，也不认识通道。
 *
 * <p>工具一旦能读"当前用户是谁"或者"回一条消息"，就只能长在 Agent 的上下文里测，
 * 那时 {@code SalesTools} / MCP / 脚本包都不再能脱离对话单独验。
 */
@AnalyzeClasses(packages = "io.github.claw4j.capability", importOptions = ImportOption.DoNotIncludeTests.class)
class ToolsBoundaryTest {

    @ArchTest
    static final ArchRule tools_must_not_depend_on_agent_or_channel =
            noClasses().that().resideInAPackage("io.github.claw4j.capability..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("io.github.claw4j.agent..", "io.github.claw4j.channel..")
                    .because("依赖方向是 capability → domain ← agent，接线只在 app 里发生，见 ADR-0010");
}
