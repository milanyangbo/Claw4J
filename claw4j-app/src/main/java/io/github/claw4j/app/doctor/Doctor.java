package io.github.claw4j.app.doctor;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code --doctor}：把"这套配置能不能用"逐项查一遍，一次报全，有 FAIL 就非 0 退出。
 *
 * <p><b>它不是第二个 health</b>，两者答的不是同一个问题：
 *
 * <table border="1">
 *   <caption>与 actuator/health 的分工</caption>
 *   <tr><th></th><th>{@code /actuator/health}</th><th>{@code --doctor}</th></tr>
 *   <tr><td>什么时候跑</td><td>运行期，被探针反复问</td><td>部署前 / 排障时，人跑一次</td></tr>
 *   <tr><td>回答什么</td><td>现在活着吗（聚合 UP/DOWN）</td><td>这套配置能不能用（逐项 + 修复动作）</td></tr>
 *   <tr><td>起不来时</td><td>一起没了</td><td>照样能跑</td></tr>
 * </table>
 *
 * <p><b>为什么能"起不来也照样跑"</b>：这里起的上下文**只加载配置，不装配任何东西**——
 * 没有 {@code @EnableAutoConfiguration}，所以没有数据源、没有 servlet 容器、没有 MCP 连接、
 * 没有模型客户端。{@code application.yml}、profile 与 {@code spring.config.import} 照常生效，
 * 因为那是 Boot 的配置数据阶段，与自动装配无关。
 *
 * <p>代价要认：这也意味着它**查不了"属性绑定本身失败"**（yaml 语法错、类型不匹配）——
 * 那种情况配置数据阶段就会抛，doctor 与主应用一起起不来。那时看启动日志，它本来就说得清。
 */
public final class Doctor {

    /** 走 doctor 而不是起应用的那个参数。 */
    public static final String FLAG = "--doctor";

    private final List<DoctorCheck> checks;

    private Doctor(List<DoctorCheck> checks) {
        this.checks = checks;
    }

    /**
     * 换一组检查。给用例用，也让以后"只跑某一项"有落点。
     *
     * @param checks 这一趟要跑的检查，顺序即报告里的顺序
     * @return 体检器
     */
    static Doctor of(List<DoctorCheck> checks) {
        return new Doctor(List.copyOf(checks));
    }

    /** 默认的那一组：都要出去的两项（key 有效性、MCP 连通性）不在里面，它们归 P18b。 */
    public static Doctor defaults() {
        return new Doctor(List.of(new MigrationCheck(), new DirectoryCheck(),
                new SecretHandleCheck(), new ConfigSanityCheck()));
    }

    /**
     * 命令行里有没有 {@code --doctor}。
     *
     * @param args 原始参数
     * @return 有则 {@code true}
     */
    public static boolean requested(String[] args) {
        return args != null && Arrays.asList(args).contains(FLAG);
    }

    /**
     * 跑一遍并返回退出码。**不调 {@code System.exit}**：退出归 {@code main}，这样测试能直接断言返回值。
     *
     * @param args 原始参数；{@code --doctor} 会被去掉再交给 Spring
     * @return 0（没有 FAIL）或 1
     */
    public int run(String[] args) {
        try (ConfigurableApplicationContext context = environment(args)) {
            return print(context.getEnvironment(), System.out);
        }
    }

    /**
     * 跑体检并写出报告。
     *
     * @param environment 配置来源
     * @param out         报告写到哪
     * @return 0 或 1
     */
    public int print(Environment environment, java.io.PrintStream out) {
        List<DoctorCheck.Finding> findings = new ArrayList<>();
        for (DoctorCheck check : checks) {
            try {
                findings.addAll(check.run(environment));
            }
            catch (RuntimeException selfInflicted) {
                // 一项自己炸了不能拖累别的：这条命令存在的理由就是"一次报全"。
                findings.add(DoctorCheck.Finding.fail(check.title(),
                        "这项检查自己出错了：" + selfInflicted, "把这段输出带上去提一个 issue"));
            }
        }
        out.println(DoctorReport.render(findings));
        return DoctorReport.exitCode(findings);
    }

    /**
     * 只加载配置的上下文：没有自动装配，起不了 web，也建不了连接池。
     *
     * @param args 原始参数
     * @return 已经起来的上下文，调用方负责关闭
     */
    static ConfigurableApplicationContext environment(String[] args) {
        List<String> withoutFlag = new ArrayList<>();
        for (String argument : args == null ? new String[0] : args) {
            if (!FLAG.equals(argument)) {
                withoutFlag.add(argument);
            }
        }
        return new SpringApplicationBuilder(DoctorConfiguration.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(withoutFlag.toArray(new String[0]));
    }
}
