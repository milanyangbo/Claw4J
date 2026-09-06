package com.claw4j.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Claw4J API Gateway service.
 */
@SpringBootApplication(scanBasePackages = "com.claw4j")
public class GatewayApplication {

    /**
     * Starts the Claw4J API Gateway service.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
