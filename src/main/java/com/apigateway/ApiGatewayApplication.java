package com.apigateway;

import com.apigateway.config.GatewayProperties;
import com.apigateway.util.JwtUtil;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class ApiGatewayApplication {

    @Autowired
    private JwtUtil jwtUtil;

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    @PostConstruct
    public void generateTestToken() {
        // Generate a valid token for a dummy user when the app starts
        String token = jwtUtil.generateToken("test-user");
        System.out.println("\n========================================================");
        System.out.println("TEST JWT TOKEN (Copy this for your bash script):");
        System.out.println(token);
        System.out.println("========================================================\n");
    }
}