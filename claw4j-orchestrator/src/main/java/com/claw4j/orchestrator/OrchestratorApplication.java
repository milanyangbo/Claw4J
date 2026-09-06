package com.claw4j.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Claw4J Orchestrator service.
 */
@SpringBootApplication
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
