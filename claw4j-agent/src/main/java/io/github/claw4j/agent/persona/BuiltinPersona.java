package io.github.claw4j.agent.persona;

import io.github.claw4j.persona.Persona;

import java.util.List;
import java.util.Optional;

/**
 * 没配 {@code claw4j.agent.directory} 时用的那份人设。
 *
 * <p><b>它为什么还留着</b>：一个只跑 HTTP 通道的最小部署不该为了"能起来"先去造一个目录。
 * 内置默认是"能干活的最小人设"，不是"某个业务的助手"——要变成销售或运维助手，
 * 配一个目录即可，那时候这份就不再生效。
 *
 * <p><b>它为什么不在 {@code AgentAutoConfiguration} 里</b>：那三行以前长在装配方法旁边，
 * 于是"改一个字"与"改装配逻辑"是同一件事。搬到这里之后，装配类只负责"用哪份人设"，
 * 不再承载内容；而"这次用的是内置的还是配的"要在启动日志里说得出来——
 * 一份谁也没注意到的内置人设，比一份写得不好的配置更难查。
 *
 * <p>它没有口径，也没有覆盖档位：这两件事都是部署的事实，内置值只能猜。
 */
public final class BuiltinPersona {

    /** 内置人设的名字。配置里点名它没有意义，但日志与用例要能引用。 */
    public static final String NAME = "builtin";

    private BuiltinPersona() {
    }

    /**
     * 能干活的最小人设：说清自己是谁、要求先查再答、不确定就问。
     *
     * <p>刻意不写业务口径。写进去的话，一个没配人设的部署会带着别人家的口径回答，
     * 而它看起来完全正常。
     */
    public static Persona get() {
        return new Persona(
                NAME,
                "内置默认人设（没有配置 claw4j.agent.directory 时使用）",
                """
                        你是 Claw4J，一个接入企业协作通道的 Agent，通过调用工具完成用户交代的活。
                        回答事实性问题前先确认口径；工具能查到的先查，查不到或不确定时向用户提问，不要猜。\
                        """,
                Optional.empty(),
                List.of());
    }
}
