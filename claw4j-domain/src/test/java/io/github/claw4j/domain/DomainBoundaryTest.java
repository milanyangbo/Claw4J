package io.github.claw4j.domain;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 把 ADR-0004 / ADR-0010 的两句话变成会失败的测试：domain 不依赖框架，port 只放契约。
 *
 * <p>这两条都不靠自觉守——`pom.xml` 里的 bannedDependencies 只拦得住"声明了依赖"，
 * 拦不住有人先在 port 包里放一个 class 再说。
 */
@AnalyzeClasses(packages = "io.github.claw4j.domain", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainBoundaryTest {

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

    @ArchTest
    static final ArchRule ports_must_only_hold_contracts =
            classes().that().resideInAPackage("io.github.claw4j.domain.port..")
                    .should().beInterfaces()
                    .orShould().beRecords()
                    .allowEmptyShould(true)
                    .because("port 是契约层，实现放在 agent 或 channel，见 ADR-0010");
}
