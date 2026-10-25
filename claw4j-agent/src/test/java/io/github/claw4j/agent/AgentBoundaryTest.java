package io.github.claw4j.agent;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构闸：Agent 不认识通道，也不把会话落到内存。
 *
 * <p>两条规矩坏掉时编译、启动、功能测试全都不会红：Agent 只是多了一条指向通道的 import，
 * 换一个通道时才暴露"要改 Agent"；会话只是换成了内存实现，重启后才暴露"记忆没了"。
 * 这种只有架构规则能提前拦住的回头路，必须由机器守着（ADR-0010 / ADR-0005）。
 */
@AnalyzeClasses(packages = "io.github.claw4j.agent", importOptions = ImportOption.DoNotIncludeTests.class)
class AgentBoundaryTest {

    @ArchTest
    static final ArchRule agent_must_not_depend_on_channel =
            noClasses().that().resideInAPackage("io.github.claw4j.agent..")
                    .should().dependOnClassesThat().resideInAPackage("io.github.claw4j.channel..")
                    .because("依赖方向是 channel → domain ← agent，接线只在 app 里发生，见 ADR-0010");

    @ArchTest
    static final ArchRule session_must_not_be_in_memory =
            noClasses().that().resideInAPackage("io.github.claw4j.agent..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.ai.session.InMemorySessionRepository")
                    .because("会话存储必须是共享的 Postgres：内存实现会在重启后静默丢掉全部记忆（ADR-0005）");
}
