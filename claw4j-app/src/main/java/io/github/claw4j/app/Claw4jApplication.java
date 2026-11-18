package io.github.claw4j.app;

import io.github.claw4j.app.doctor.Doctor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 装配入口。唯一把 domain / agent / tools / channel 拼到一起的地方。
 *
 * <p>两种起法，判断只看一个参数：
 * <ul>
 *   <li>带 {@code --doctor}：跑一遍体检，打印报告，按结果退出（0 / 1）。见 {@link Doctor}。</li>
 *   <li>其余情况：起完整的应用。</li>
 * </ul>
 * 不拆成两个启动类，是因为多个 main 会让 {@code spring-boot-maven-plugin} 的 repackage
 * 找不到唯一的启动类（要么配 {@code mainClass}、要么每次额外传参），而"一条命令两种用途"
 * 正好是部署脚本想要的形状。
 */
@SpringBootApplication
public class Claw4jApplication {

    /**
     * 启动 Spring 容器，或者在 {@code --doctor} 时跑体检。
     *
     * @param args 命令行参数；除 {@code --doctor} 外原样交给 {@link SpringApplication#run}
     */
    public static void main(String[] args) {
        if (Doctor.requested(args)) {
            System.exit(Doctor.defaults().run(args));
        }
        SpringApplication.run(Claw4jApplication.class, args);
    }
}
