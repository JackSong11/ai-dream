package com.example.dream.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.example.dream")
public class DreamAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(DreamAdminApplication.class, args);
    }

}