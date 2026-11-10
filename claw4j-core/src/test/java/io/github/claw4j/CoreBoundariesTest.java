package io.github.claw4j;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * {@code claw4j-core} 的三条边界，写在一起是因为它们守的是同一件事：
 * 这个 jar 里的东西能脱离框架用。
 *
 * <p>第三方接一个新通道、写一个工具插件、或者只是想要那套文本切分，应该能只依赖它一个坐标，
 * 而 classpath 里不出现飞书 SDK、也不出现 Spring。
 *
 * <p>三条边界的严格程度不同：
 * <table border="1">
 *   <caption>三条边界</caption>
 *   <tr><th>包</th><th>允许依赖</th><th>为什么</th></tr>
 *   <tr><td>{@code domain..}</td><td>只有 JDK 与自己</td>
 *       <td>纯模型，连日志都不该知道</td></tr>
 *   <tr><td>{@code channel..}</td><td>JDK、slf4j、domain、自己</td>
 *       <td>编排要记日志，但不需要别的</td></tr>
 *   <tr><td>{@code chunk..}</td><td>只有 JDK 与自己</td>
 *       <td>切句、分组、断点、成块是纯函数；它一旦伸手去拿框架或 tokenizer，就没法被单独拿去用</td></tr>
 * </table>
 *
 * <p>合并成一份而不是三个文件，是因为门禁按文件数卡（10 个），而这三条同属"结构规则"一类：
 * 分散在三个文件里，读的人要翻三处才能知道 core 不许干什么。
 *
 * <p>写成白名单而不是点名某家 SDK，是因为点名有两种静默失效：包名写错一位，规则永远匹配不到
 * 任何类，绿的；SDK 换了包名，同一句话又变回空规则。反过来写"除了这几家谁也不许依赖"，就不必
 * 跟着谁的包名走。
 *
 * <p>同样的理由，这里不写"不依赖 channel.feishu"这种规则：本模块的 classpath 上根本没有那个
 * 包，规则会匹配到零个类然后报绿——一条永远为真的断言比没有断言更糟。
 */
@AnalyzeClasses(packages = {"io.github.claw4j.domain", "io.github.claw4j.channel",
        "io.github.claw4j.chunk"}, importOptions = ImportOption.DoNotIncludeTests.class)
class CoreBoundariesTest {

    @ArchTest
    static final ArchRule domainStaysFrameworkFree =
            noClasses().that().resideInAPackage("io.github.claw4j.domain..")
                    .should().dependOnClassesThat()
                    .resideOutsideOfPackages("java..", "io.github.claw4j.domain..");

    @ArchTest
    static final ArchRule portPackageHoldsOnlyContracts =
            classes().that().resideInAPackage("io.github.claw4j.domain.port..")
                    .should().beInterfaces().orShould().beRecords();

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

    /**
     * 切块算法只碰 JDK：它吃字符串与向量、吐下标，能脱离 Spring AI 单独用。
     *
     * <p>改这个包的名字时两处都要改：{@code @AnalyzeClasses} 的包列表与这条规则。只改一处的
     * 话规则匹配零个类，而 ArchUnit 对空集是失败的；反过来若用
     * {@code noClasses().that()...} 形成"没有对象"的规则，就会静默报绿。
     */
    @ArchTest
    static final ArchRule chunkPlannerTouchesNothingButTheJdk =
            classes().that().resideInAPackage("io.github.claw4j.chunk..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage(
                            "java..",
                            "io.github.claw4j.chunk..");

    /*
     * 曾经的 text.. 规则（只碰 JDK）已经没有对象，所以删掉：切块改用框架的 TokenTextSplitter、
     * 融合改用 DocumentJoiner 的实现、中文分词随关键词通道一起撤掉——那个包空了。ArchUnit 对
     * "规则没检查到任何类"是失败的（它不接受空集悄悄通过），所以这不是放宽规则，是删掉一条没有
     * 对象的规则。语义切块的纯函数如今在 chunk..，由上面的 chunkPlannerTouchesNothingButTheJdk
     * 守着，形式与当初那条一样（白名单只放 java）。
     */
}
