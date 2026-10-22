package io.github.claw4j.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 装配入口。唯一把 domain / agent / tools / channel 拼到一起的地方。
 */
@SpringBootApplication
public class Claw4jApplication {

    public static void main(String[] args) {
        SpringApplication.run(Claw4jApplication.class, args);
    }
}
