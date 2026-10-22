package io.github.claw4j.domain;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 把 ADR-0004 里"domain 不依赖框架"这句话变成会失败的测试。
 *
 * <p>写这条规则的起因：1.x 里没有边界约束，业务类里随手 import Spring 的东西，
 * 等到想复用时才发现搬不动。口头约定守不住边界，机器能。
 */
@AnalyzeClasses(packages = "io.github.claw4j.domain")
class ArchitectureTest {

    /**
     * domain 不得依赖任何框架、厂商 SDK 或响应式库。
     * 这条一旦红，说明有人把基础设施概念漏进了领域层，要在 ADR 里重新讨论而不是直接放开。
     */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_frameworks =
            noClasses().that().resideInAPackage("io.github.claw4j.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "org.springaicommunity..",
                            "com.larksuite..",
                            "com.lark..",
                            "reactor..",
                            "io.micrometer..")
                    .because("domain 必须能脱离任何框架独立编译与复用，见 ADR-0004 与 ADR-0010");

    /**
     * port 包只放契约：接口（行为约定）与 record（消息载体）。
     * 一旦这里有普通 class，说明实现混进了契约层，装配关系就开始黏。
     */
    @ArchTest
    static final ArchRule ports_must_only_hold_contracts =
            classes().that().resideInAPackage("io.github.claw4j.domain.port..")
                    .should().beInterfaces()
                    .orShould().beRecords()
                    .allowEmptyShould(true)
                    .because("port 是契约层，实现放在 agent 或 channel，见 ADR-0010");
}
