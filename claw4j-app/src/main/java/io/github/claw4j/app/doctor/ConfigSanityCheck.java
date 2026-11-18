package io.github.claw4j.app.doctor;

import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置里"写了但不会照做的"那些组合。
 *
 * <p>这一项的判据只有一条：**这行配置会不会产生它字面上的效果**。不会就是一条 FAIL，
 * 因为那种失效最贵——配置看着是对的，出问题时人还在配置里翻。
 *
 * <p>查三处（都在 {@code claw4j.tools.sandbox} 下，它们今天都有一半是"配了不生效"）：
 * <ul>
 *   <li>{@code backend=ssh}：未实现，启动即失败；</li>
 *   <li>{@code mode=non-main}：判定函数有了、"这一段算不算主对话"还没接上，启动即失败；</li>
 *   <li>{@code mode=all} 且 {@code workspace-access=ro}：只读挂载今天不生效（挂载由库的
 *       {@code DockerCliExecBackend} 管，只支持读写）。</li>
 * </ul>
 *
 * <p>不探 docker 本身：探它要起一个容器，那是有副作用的动作，doctor 只读。起不来的是启动期
 * fail-fast 说的事（库的 {@code build()} 抛出来，指名道姓）。
 */
public final class ConfigSanityCheck implements DoctorCheck {

    private static final String MODE = "claw4j.tools.sandbox.mode";

    private static final String BACKEND = "claw4j.tools.sandbox.backend";

    private static final String WORKSPACE_ACCESS = "claw4j.tools.sandbox.workspace-access";

    @Override
    public String title() {
        return "配置组合";
    }

    @Override
    public List<Finding> run(Environment environment) {
        String mode = value(environment, MODE, "off");
        String backend = value(environment, BACKEND, "docker");
        String access = value(environment, WORKSPACE_ACCESS, "ro");
        List<Finding> findings = new ArrayList<>();

        if ("ssh".equals(backend)) {
            findings.add(Finding.fail("沙箱后端", BACKEND + "=ssh 还没有实现（只有 docker 一种后端）",
                    "把 backend 改成 docker，或者把 mode 关掉（" + MODE + "=off）"));
        }
        if ("non-main".equals(mode)) {
            findings.add(Finding.fail("沙箱范围", MODE + "=non-main 还没有接上"
                    + "（\"这一段算不算主对话\"要在工具清单那一层判），配了启动即失败",
                    "改成 off 或 all；要按会话区分沙箱，先开一张票把判定接上"));
        }
        if (!"off".equals(mode) && "ro".equals(access)) {
            findings.add(Finding.fail("工作区挂载", WORKSPACE_ACCESS + "=ro 今天不生效："
                    + "exec 侧的挂载由库管、只支持读写，容器里的命令能改工作区",
                    "改成 rw（承认这一点），或等 P17b 把只读根做出来（它 blocked 在上游加限额参数）"));
        }
        if (findings.isEmpty()) {
            findings.add(Finding.pass(title(), "沙箱 mode=" + mode + " / backend=" + backend
                    + " / 挂载 " + access + "，没有\"配了不生效\"的组合"));
        }
        return findings;
    }

    private static String value(Environment environment, String key, String fallback) {
        String configured = environment.getProperty(key);
        return configured == null || configured.isBlank() ? fallback : configured.strip().toLowerCase();
    }
}
