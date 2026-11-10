package io.github.claw4j.agent.persona;

import io.github.claw4j.persona.Persona;

import java.util.List;
import java.util.Optional;

/**
 * 没配 {@code claw4j.agent.directory} 时用的那份人设。
 *
 * <p>留着它是因为一个只跑 HTTP 通道的最小部署不该为了能起来先去造一个目录。内置的是「能干活
 * 的最小人设」，不是某个业务的助手：要变成销售或运维助手，配一个目录即可，那时这份就不再
 * 生效。
 *
 * <p>内容与装配分开，改一个字才不会变成改装配逻辑。装配类只负责「用哪份人设」，不承载内容；
 * 而「这次用的是内置的还是配的」要在启动日志里说得出来，一份谁也没注意到的内置人设比一份
 * 写得不好的配置更难查。
 *
 * <p>它没有口径，也没有覆盖档位：这两件事都是部署的事实，内置值只能猜。
 */
public final class BuiltinPersona {

    /** 内置人设的名字。配置里点名它没有意义，但日志与用例要能引用。 */
    public static final String NAME = "builtin";

    private BuiltinPersona() {
    }

    /**
     * 造一份能干活的最小人设：说清自己是谁、要求先查再答、不确定就问。
     *
     * <p>刻意不写业务口径。写进去的话，一个没配人设的部署会带着别人家的口径回答，而它看起来
     * 完全正常。
     *
     * @return 内置人设
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
