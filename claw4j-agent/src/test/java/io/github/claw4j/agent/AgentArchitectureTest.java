package io.github.claw4j.agent;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Agent 层的边界。与 {@code ChannelArchitectureTest} 里的规则成对存在——
 * 两边各自声明"我不认识对方"，依赖方向才真的成立。
 */
@AnalyzeClasses(packages = "io.github.claw4j.agent")
class AgentArchitectureTest {

    /**
     * Agent 不认识任何通道。它只收 domain 的 InboundMessage、吐 OutboundMessage，
     * 消息从哪来跟它无关。
     */
    @ArchTest
    static final ArchRule agent_must_not_depend_on_channel =
            noClasses().that().resideInAPackage("io.github.claw4j.agent..")
                    .should().dependOnClassesThat().resideInAPackage("io.github.claw4j.channel..")
                    .because("依赖方向是 channel → domain ← agent，接线只在 app 里发生，见 ADR-0010");
}
