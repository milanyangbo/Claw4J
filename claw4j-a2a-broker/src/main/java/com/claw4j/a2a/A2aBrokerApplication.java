package com.claw4j.a2a;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Claw4J A2A Broker service.
 */
@SpringBootApplication(scanBasePackages = "com.claw4j")
public class A2aBrokerApplication {

    /**
     * Starts the Claw4J A2A Broker service.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(A2aBrokerApplication.class, args);
    }
}
