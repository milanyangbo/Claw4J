package io.github.claw4j.channel;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * channel 的依赖方向：只认识 domain 里的端口，不认识 Agent 的实现，也不认识能力。
 *
 * <p>反向依赖不会编译失败：channel 一旦 import 了 Agent 的具体类，换一个 Agent 实现就要改所有通道，
 * 通道的单测也得先起整个 Agent；反过来依赖 capability，则"换个通道"会把工具一起拖进来。
 * 两条都是代码照跑、日志干净、只是边界没了。
 */
@AnalyzeClasses(packages = "io.github.claw4j.channel", importOptions = ImportOption.DoNotIncludeTests.class)
class ChannelBoundaryTest {

    @ArchTest
    static final ArchRule channelReachesTheAgentOnlyThroughThePort =
            noClasses().that().resideInAPackage("io.github.claw4j.channel..")
                    .should().dependOnClassesThat().resideInAPackage("io.github.claw4j.agent..");

    @ArchTest
    static final ArchRule channelDoesNotDependOnCapability =
            noClasses().that().resideInAPackage("io.github.claw4j.channel..")
                    .should().dependOnClassesThat().resideInAPackage("io.github.claw4j.capability..");

    /**
     * 入站调度那一层不依赖任何外部实现，飞书 SDK 自然也在内。
     *
     * <p>这里刻意<b>不写厂商包名</b>。写具体包名有两种静默失效：名字写错一位，规则永远匹配不到任何类，
     * 绿的；SDK 哪天换了包名，同一句话又变回空规则。而这类规则失效不会报错，只会让人以为"有东西守着"。
     * 反过来写成"除了 JDK、日志、自家代码，谁也不许依赖"，就不必跟着谁的包名走。
     *
     * <p>范围写的是 {@code channel.feishu} 而不是 {@code channel.feishu..}：SDK 绑定放在子包
     * {@code channel.feishu.sdk}，它当然要用 SDK 的类型。子包由下一条规则单独拦，两条合起来才是分界——
     * 调度层既碰不到 SDK，也拿不到自己那个 sdk 子包里的翻译类。
     */
    @ArchTest
    static final ArchRule dispatchLayerDependsOnNothingButItsOwnContracts =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.feishu")
                    .should().dependOnClassesThat().resideOutsideOfPackages(
                            "java..", "org.slf4j..", "io.github.claw4j..");

    /**
     * 调度层也不许反过来依赖自己那个 SDK 子包——这条比上一条更容易被绕过。
     *
     * <p>没人会把 {@code com.lark.oapi} 的 import 写进调度层，但"顺手用一下 sdk 包里的翻译类"很自然：
     * 那样 {@code InboundDispatcher} 一编译就得有 SDK 在场，默认构建里那十几条用例先挂。
     * 两条规则合起来才是真正的分界：调度层谁都不依赖自己人以外的 SDK 知识。
     */
    @ArchTest
    static final ArchRule dispatchLayerDoesNotReachIntoItsSdkSubpackage =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.feishu")
                    .should().dependOnClassesThat().resideInAPackage("io.github.claw4j.channel.feishu.sdk..");

    /**
     * 平台中立的两个包不许反过来依赖某个平台。
     *
     * <p>反方向（{@code domain} 依赖 {@code channel}）改不出来：domain 的 pom 里没有 channel，
     * 编译期就断了。所以只需要盯住 spi——它一旦为了"顺手"引用某个通道的事件类型，
     * 下一个通道就得先认识上一个通道的语义。
     */
    @ArchTest
    static final ArchRule contractPackageStaysPlatformNeutral =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.spi..")
                    .should().dependOnClassesThat().resideInAPackage("io.github.claw4j.channel.feishu..");
}
