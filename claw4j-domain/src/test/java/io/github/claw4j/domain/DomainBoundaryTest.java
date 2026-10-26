package io.github.claw4j.domain;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * domain 的两条边界：不许碰框架，端口包只放契约。
 *
 * <p>两条坏了都不报错：引一个 Spring 类型照样编译、照样跑，只是这个模块从此不能脱离框架复用；
 * 端口包里落一个带实现的类，调用方就会开始依赖它，端口不再是契约而是一份实现。
 */
@AnalyzeClasses(packages = "io.github.claw4j.domain", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainBoundaryTest {

    @ArchTest
    static final ArchRule domainStaysFrameworkFree =
            noClasses().that().resideInAPackage("io.github.claw4j.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "org.springaicommunity..",
                            "com.larksuite..",
                            "io.micrometer..",
                            "io.projectreactor..",
                            "jakarta..");

    @ArchTest
    static final ArchRule portPackageHoldsOnlyContracts =
            classes().that().resideInAPackage("io.github.claw4j.domain.port..")
                    .should().beInterfaces().orShould().beRecords();
}
