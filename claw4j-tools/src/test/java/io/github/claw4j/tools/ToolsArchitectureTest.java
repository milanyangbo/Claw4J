package io.github.claw4j.tools;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Tools 层的边界。与 agent / channel 各自的架构测试成对存在，
 * 三边都声明"我不认识对方"，依赖方向才是真的而不是画在图上的。
 */
@AnalyzeClasses(packages = "io.github.claw4j.tools", importOptions = ImportOption.DoNotIncludeTests.class)
class ToolsArchitectureTest {

    /**
     * 工具不认识 Agent 与通道。
     *
     * <p>为什么这条值得单独一张票就立上：工具是要被 AI 调的，最自然的写法就是
     * "工具里顺便问一句当前用户是谁、顺便回一条消息"，这么一写工具就长进了 Agent 里，
     * 从此只能在 Agent 的上下文里测。工具应当只认参数和返回值（ADR-0003 第一层）。
     */
    @ArchTest
    static final ArchRule tools_must_not_depend_on_agent_or_channel =
            noClasses().that().resideInAPackage("io.github.claw4j.tools..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.claw4j.agent..", "io.github.claw4j.channel..")
                    .because("工具只依赖 domain；谁在调用它、结果发去哪个通道都与它无关，见 ADR-0010");
}
