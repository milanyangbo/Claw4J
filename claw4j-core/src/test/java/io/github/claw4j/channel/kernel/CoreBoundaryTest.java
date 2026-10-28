package io.github.claw4j.channel.kernel;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * core 里 channel 那半边的依赖边界：只有 JDK、slf4j、domain、以及自己。
 *
 * <p>这条规则要守的是 {@code claw4j-core} 存在的理由：第三方接一个新通道、或者写一个工具插件，
 * 应该能<b>只依赖它一个坐标</b>，而 classpath 里不出现飞书 SDK、也不出现 Spring。
 * 一旦这里 import 了任何框架或 SDK 的类型（哪怕只是"顺手用一下那个常量"），整件事就白做了：
 * 第三方要么把 SDK 拖进来，要么自己再抄一份编排。
 *
 * <p><b>为什么写成白名单而不是点名飞书</b>：点名写法有两种静默失效——包名写错一位，
 * 规则永远匹配不到任何类，绿的；SDK 换了包名，同一句话又变回空规则。
 * 反过来写"除了这几家谁也不许依赖"，就不必跟着谁的包名走，而且对下一个 SDK 也成立。
 *
 * <p>同样的理由，这里不写"不依赖 channel.feishu"这种规则：本模块的 classpath 上
 * 根本没有那个包，规则会匹配到零个类然后报绿——一条永远为真的断言比没有断言更糟。
 *
 * <p><b>为什么没和 {@code DomainBoundaryTest} 合成一条</b>：那条更严，管的是
 * {@code io.github.claw4j.domain} 不许依赖<b>任何</b>外部包（连 slf4j 都不行）——
 * domain 是连日志都不该知道的纯模型。这条允许 slf4j，因为编排要记日志。
 * 两个包现在在同一个 jar 里（ADR-0015），但两条边界不是一回事。
 */
@AnalyzeClasses(packages = "io.github.claw4j.channel", importOptions = ImportOption.DoNotIncludeTests.class)
class CoreBoundaryTest {

    @ArchTest
    static final ArchRule channelSideOfCoreDependsOnNothingButTheJdkSlf4jAndDomain =
            classes().that().resideInAPackage("io.github.claw4j.channel..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage(
                            "java..",
                            "org.slf4j..",
                            "io.github.claw4j.domain..",
                            "io.github.claw4j.channel..");

    /**
     * 编排层不知道回答是怎么算出来的：它只调 {@code AgentPort}。
     *
     * <p>这条与上面那条有重叠（agent 不在白名单里），单独写出来是因为它值得一句人话：
     * 一旦有人为了"顺手拿一下会话"去 import Agent 的实现类，通道就没法脱离 Agent 单独测了。
     */
    @ArchTest
    static final ArchRule kernelReachesTheAgentOnlyThroughThePort =
            classes().that().resideInAPackage("io.github.claw4j.channel.kernel..")
                    .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                            "io.github.claw4j.agent..",
                            "io.github.claw4j.capability..");
}
