package io.github.claw4j.agent;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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

    /**
     * 本仓库自己的 Advisor 必须同时挂上同步与流式两条路径。
     *
     * <p>不是洁癖：流式链取的是 {@code getStreamAdvisors()}，只实现 {@code CallAdvisor} 的
     * Advisor 在 SSE 上会被**静默跳过**——编译过、容器起得来、日志里一句没有，
     * 表现是"这条约束在流式入口上根本不存在"。而我们的 HTTP 通道同时提供 call 与 SSE，
     * 少挂一条路径等于少一个入口的防线。
     *
     * <p>做成规则而不是靠人记：漏实现一个接口不会有任何报错。
     */
    @ArchTest
    static final ArchRule advisorsCoverBothPaths =
            classes().that().resideInAPackage("io.github.claw4j.agent.advisor..")
                    .and().implement(Advisor.class)
                    .should().implement(CallAdvisor.class)
                    .andShould().implement(StreamAdvisor.class);
}
