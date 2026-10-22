package io.github.claw4j.channel;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 通道模块内部的边界。
 *
 * <p>拆模块能强制边界，但代价是每加一个通道都要新建 POM、改父 POM、改 CI 矩阵
 * （ADR-0010 决定不再这么干）。合并成单模块后，边界改由这里的包级规则兜住：
 * 粒度更细，成本更低——它连"SPI 包里混进一个平台 SDK import"都能拦下来，
 * 而这是模块隔离做不到的（模块隔离只能拦住整个模块级别的依赖）。
 */
@AnalyzeClasses(packages = "io.github.claw4j.channel")
class ChannelArchitectureTest {

    /**
     * SPI 必须对所有平台中立。飞书 SDK 进 spi 包，等于每个新通道都被飞书绑架。
     */
    @ArchTest
    static final ArchRule spi_must_be_platform_neutral =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.spi..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.larksuite..",
                            "com.lark..",
                            "org.telegram..",
                            "org.springframework.web..")
                    .because("SPI 是全部通道的共同契约，不能出现任何具体平台或传输方式");

    /**
     * 通道不知道回答是怎么算出来的，它只认 domain 里的 AgentPort。
     */
    @ArchTest
    static final ArchRule channel_must_not_depend_on_agent =
            noClasses().that().resideInAPackage("io.github.claw4j.channel..")
                    .should().dependOnClassesThat().resideInAPackage("io.github.claw4j.agent..")
                    .because("依赖方向是 channel → domain ← agent，接线只在 app 里发生，见 ADR-0010");

    /**
     * 平台 SDK 只准出现在自己的实现包里。
     */
    @ArchTest
    static final ArchRule lark_sdk_confined_to_feishu_package =
            noClasses().that().resideInAPackage("io.github.claw4j.channel..")
                    .and().resideOutsideOfPackage("io.github.claw4j.channel.feishu..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.larksuite..", "com.lark..")
                    .because("飞书 SDK 以 optional 引入，只应在飞书实现包里被引用");
}
