package io.github.claw4j.agent;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Agent 层的架构闸。
 *
 * <p>反向依赖不会编译失败，也不会当场报错：agent 一旦认识 channel，
 * 换一个入口就要改 Agent，单元测试也得先起一个通道。ADR-0010 的边界在这里落成机器校验。
 */
@AnalyzeClasses(packages = "io.github.claw4j.agent", importOptions = ImportOption.DoNotIncludeTests.class)
class AgentBoundaryTest {

    @ArchTest
    static final ArchRule agentKnowsNoChannel =
            noClasses().that().resideInAPackage("io.github.claw4j.agent..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("io.github.claw4j.channel..");
}
