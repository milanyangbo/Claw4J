package io.github.claw4j.capability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 把「启动时的工作目录」记成一个属性：{@code claw4j.launch-directory}。
 *
 * <p>为什么需要它：{@code Path.of(".").toAbsolutePath()} 在 Servlet 应用里**不是启动目录**。
 * 内嵌 Tomcat 起来的时候会把 {@code user.dir} 改成它的 docbase
 * （实测：`/var/folders/…/T/tomcat-docbase.8080.…`），于是任何在 bean 里解析 `.` 的地方
 * 都落到那个临时目录上。表现是"`Bash` 的 `pwd` 不是仓库根"、"Read 说仓库里的文件在允许目录之外"——
 * 两个都看着像权限问题，其实是路径解析问题。
 *
 * <p>时机：{@code EnvironmentPostProcessor} 在环境准备好的时候就跑了，比 Tomcat 起来早得多，
 * 那时 {@code user.dir} 还是真的启动目录。
 *
 * <p>写成属性而不是静态字段：`/actuator/env` 与启动日志里都能看见它，排查"为什么默认目录是这个"
 * 时有据可查；静态字段只存在于某个类的加载顺序里。
 *
 * <p>用 {@code addLast}：这一条是兜底默认值，配置里真写了同名键时以配置为准。
 */
public class LaunchDirectoryEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 属性名。配置里不要手写它，它由这里在启动时填。 */
    public static final String PROPERTY = "claw4j.launch-directory";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String directory = System.getProperty("user.dir");
        if (directory == null || directory.isBlank()) {
            return;
        }
        environment.getPropertySources()
                .addLast(new MapPropertySource("claw4jLaunchDirectory", Map.of(PROPERTY, directory)));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
