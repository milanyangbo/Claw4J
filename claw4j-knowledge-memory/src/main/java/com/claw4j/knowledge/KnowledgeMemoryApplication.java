package com.claw4j.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Entry point for the Claw4J Knowledge Memory service.
 */
@SpringBootApplication(scanBasePackages = "com.claw4j")
@EnableDiscoveryClient
public class KnowledgeMemoryApplication {

    /**
     * Starts the Claw4J Knowledge Memory service.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(KnowledgeMemoryApplication.class, args);
    }
}
