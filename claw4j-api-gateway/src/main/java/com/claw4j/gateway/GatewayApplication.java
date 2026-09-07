package com.claw4j.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Entry point for the Claw4J API Gateway service.
 */
@SpringBootApplication(scanBasePackages = "com.claw4j")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.claw4j.gateway.client")
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
