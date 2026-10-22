package io.github.claw4j.agent;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Agent 层的边界。与 {@code ChannelArchitectureTest} 里的规则成对存在——
 * 两边各自声明"我不认识对方"，依赖方向才真的成立。
 */
// 只看主代码：AdvisorBothPathsTest 里故意留了一个只实现 CallAdvisor 的反面用例，
// 它是"被规则拦住的东西长什么样"的演示，不该反过来让规则失败。
@AnalyzeClasses(packages = "io.github.claw4j.agent", importOptions = ImportOption.DoNotIncludeTests.class)
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

    /**
     * 每个 Advisor 都必须同时实现同步与流式两侧。
     *
     * <p>为什么值得单独立一条规则：流式路径从 {@code BaseAdvisorChain#getStreamAdvisors()}
     * 取 Advisor，只认 {@code StreamAdvisor}。只写 {@code CallAdvisor} 的鉴权 Advisor
     * 在流式路径上会**静默失效**——不报错、不告警，鉴权等于没做。后果见
     * {@code AdvisorBothPathsTest}。这种"能编译、能跑、但悄悄不生效"的缺陷只能靠机器拦。
     */
    @ArchTest
    static final ArchRule advisors_must_implement_call_side =
            classes().that().areAssignableTo(Advisor.class)
                    .should().beAssignableTo(CallAdvisor.class)
                    .because("Advisor 必须双实现，否则同步路径会漏掉它（ADR-0007）")
                    // M0 还没有 Advisor，规则暂时匹配不到任何类；等鉴权 Advisor 进来它就有东西拦了
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule advisors_must_implement_stream_side =
            classes().that().areAssignableTo(Advisor.class)
                    .should().beAssignableTo(StreamAdvisor.class)
                    .because("Advisor 必须双实现，否则流式路径会静默跳过它（ADR-0007）")
                    .allowEmptyShould(true);
}
