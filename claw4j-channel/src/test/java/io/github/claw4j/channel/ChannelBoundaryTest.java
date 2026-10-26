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
}
