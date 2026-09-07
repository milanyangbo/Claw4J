package com.claw4j.tool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Entry point for the Claw4J Tool Executor service.
 */
@SpringBootApplication(scanBasePackages = "com.claw4j")
@EnableDiscoveryClient
public class ToolExecutorApplication {

    /**
     * Starts the Claw4J Tool Executor service.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(ToolExecutorApplication.class, args);
    }
}
