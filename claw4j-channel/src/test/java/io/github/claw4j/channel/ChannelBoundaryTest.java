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
 * 通道的单测也得先起整个 Agent；反过来依赖 capability，"换个通道"会把工具一起拖进来。
 * 两条都是代码照跑、日志干净、只是边界没了。
 *
 * <p>契约与编排不在这里：{@code channel.spi} 与 {@code channel.kernel} 在 {@code claw4j-core}，
 * 它们的边界由那个模块的 {@code CoreBoundariesTest} 用白名单守着（只许依赖 JDK、slf4j、
 * domain 与自家代码），比本类这几条更严。本类只守这个模块里的平台实现（飞书、企业微信、
 * 钉钉、Slack、HTTP）不反向依赖 Agent 与能力，以及每个平台的适配层不认识自己的实现子包。
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
     * 飞书这一侧的适配层不依赖任何外部实现，SDK 自然也在内。
     *
     * <p>这个包只有两样东西：{@code FeishuInboundEvent}（把飞书事件压成内核的五个字段）
     * 与 {@code FeishuReplySender}（把一段文本推回去）。编排逻辑在 {@code channel.kernel}，
     * 它连这个包都不认识。
     *
     * <p>刻意不写厂商包名。写具体包名有两种静默失效：名字写错一位，规则永远匹配不到任何类，
     * 测试仍是绿的；SDK 哪天换了包名，同一句话又变回空规则。写成"除了 JDK、日志、自家代码，
     * 谁也不许依赖"，就不必跟着谁的包名走。
     *
     * <p>范围写 {@code channel.feishu} 而不是 {@code channel.feishu..}：SDK 绑定放在子包
     * {@code channel.feishu.sdk}，它当然要用 SDK 的类型。子包由下一条规则单独拦，
     * 两条合起来才是分界。
     */
    @ArchTest
    static final ArchRule dispatchLayerDependsOnNothingButItsOwnContracts =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.feishu")
                    .should().dependOnClassesThat().resideOutsideOfPackages(
                            "java..", "org.slf4j..", "io.github.claw4j..");

    /**
     * 适配层也不许反过来依赖自己那个 SDK 子包，这条比上一条更容易被绕过。
     *
     * <p>没人会把 {@code com.lark.oapi} 的 import 写进适配层，但"顺手用一下 sdk 包里的翻译类"很自然：
     * 那样 {@code FeishuInboundEvent} 一编译就得有 SDK 在场，默认构建里那些用例先挂。
     */
    @ArchTest
    static final ArchRule dispatchLayerDoesNotReachIntoItsSdkSubpackage =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.feishu")
                    .should().dependOnClassesThat().resideInAPackage("io.github.claw4j.channel.feishu.sdk..");

    /**
     * 企业微信这一侧的同一条界：{@code channel.wecom} 里只有三个类型
     * （入站事件的适配、回推的出口、投递实现），它们不许认识 HTTP、XML、加解密。
     *
     * <p>不并进上面那条：两条规则守的是同一件事（适配层不依赖实现），但作用在两个包上。
     * 写成 {@code resideInAnyPackage} 少两行，代价是失败信息里那个类名不再直接指向是哪个通道破了界，
     * 而这条规则唯一的价值就是这个。
     */
    @ArchTest
    static final ArchRule wecomDispatchLayerDependsOnNothingButItsOwnContracts =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.wecom")
                    .should().dependOnClassesThat().resideOutsideOfPackages(
                            "java..", "org.slf4j..", "io.github.claw4j..");

    /**
     * 同一条界限的另一半：适配层也不许反过来依赖自己的实现子包。
     *
     * <p>企业微信没有厂商 SDK，所以子包叫 {@code client} 而不是 {@code sdk}：那里是
     * 加解密、XML、换 token。适配层一旦伸手去拿，调度层的用例就得多一个 {@code WecomCipher} 才能跑。
     */
    @ArchTest
    static final ArchRule wecomDispatchLayerDoesNotReachIntoItsClientSubpackage =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.wecom")
                    .should().dependOnClassesThat().resideInAPackage("io.github.claw4j.channel.wecom.client..");

    /**
     * 钉钉这一侧的同一条界。
     *
     * <p>额外一层意义：适配层里有 {@code DingtalkReplySender}，而实现那份要用 HMAC 与 HTTP。
     * 一旦适配层伸手去 client 包里拿 {@code DingtalkSignature}，{@code DingtalkInboundEvent}
     * 一编译就得有加密在场，那条"五个字段"的接口就再也编不进一个只有 JDK 的地方。
     */
    @ArchTest
    static final ArchRule dingtalkDispatchLayerDependsOnNothingButItsOwnContracts =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.dingtalk")
                    .should().dependOnClassesThat().resideOutsideOfPackages(
                            "java..", "org.slf4j..", "io.github.claw4j..");

    @ArchTest
    static final ArchRule dingtalkDispatchLayerDoesNotReachIntoItsClientSubpackage =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.dingtalk")
                    .should().dependOnClassesThat().resideInAPackage("io.github.claw4j.channel.dingtalk.client..");

    /**
     * Slack 这一侧的同一条界。
     *
     * <p>这个包的形状与前三份不完全一样：多了一个 {@code SlackConversation}，把"频道 + 线程"
     * 压成 {@code conversationId} 的纯字符串规则。它留在适配层是有意的：编码与解码必须成对，
     * 而解码那一半在 {@code client} 包里（发送器要用）。反过来，如果把它塞进 {@code client}，
     * 适配层就得伸进子包去拿它，这条规则会红。
     */
    @ArchTest
    static final ArchRule slackDispatchLayerDependsOnNothingButItsOwnContracts =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.slack")
                    .should().dependOnClassesThat().resideOutsideOfPackages(
                            "java..", "org.slf4j..", "io.github.claw4j..");

    @ArchTest
    static final ArchRule slackDispatchLayerDoesNotReachIntoItsClientSubpackage =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.slack")
                    .should().dependOnClassesThat().resideInAPackage("io.github.claw4j.channel.slack.client..");
}
