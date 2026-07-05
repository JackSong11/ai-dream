package com.example.dream.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.example.dream")
@MapperScan("com.example.dream.dal.mapper")
public class DreamAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(DreamAppApplication.class, args);
    }

}