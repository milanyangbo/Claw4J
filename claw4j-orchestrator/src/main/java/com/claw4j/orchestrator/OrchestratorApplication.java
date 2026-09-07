package com.claw4j.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Entry point for the Claw4J Orchestrator service.
 */
@SpringBootApplication(scanBasePackages = "com.claw4j")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.claw4j.orchestrator.client")
public class OrchestratorApplication {

    /**
     * Starts the Claw4J Orchestrator service.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
