package io.github.claw4j.channel;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 通道模块的边界（ADR-0010）。三边各自声明"不认识对方"，依赖方向才是真的，
 * 而不是画在图上的一句话。
 */
@AnalyzeClasses(packages = "io.github.claw4j.channel", importOptions = ImportOption.DoNotIncludeTests.class)
class ChannelBoundaryTest {

    @ArchTest
    static final ArchRule spi_must_be_platform_neutral =
            noClasses().that().resideInAPackage("io.github.claw4j.channel.spi..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.larksuite..",
                            "com.lark..",
                            "org.telegram..",
                            "org.springframework.web..")
                    .because("SPI 是全部通道的共同契约，不能出现任何具体平台或传输方式");

    @ArchTest
    static final ArchRule channel_must_not_depend_on_agent =
            noClasses().that().resideInAPackage("io.github.claw4j.channel..")
                    .should().dependOnClassesThat().resideInAPackage("io.github.claw4j.agent..")
                    .because("依赖方向是 channel → domain ← agent，接线只在 app 里发生，见 ADR-0010");

    @ArchTest
    static final ArchRule lark_sdk_confined_to_feishu_package =
            noClasses().that().resideInAPackage("io.github.claw4j.channel..")
                    .and().resideOutsideOfPackage("io.github.claw4j.channel.feishu..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.larksuite..", "com.lark..")
                    .because("飞书 SDK 以 optional 引入，只应在飞书实现包里被引用");
}
