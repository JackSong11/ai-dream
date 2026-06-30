package com.example.dream.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.example.dream")
public class DreamAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(DreamAppApplication.class, args);
    }

}