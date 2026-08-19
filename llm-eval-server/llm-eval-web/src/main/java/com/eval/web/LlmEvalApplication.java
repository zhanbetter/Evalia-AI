package com.eval.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.eval")
@EnableAsync
@EnableScheduling
@MapperScan("com.eval.dao.mapper")
public class LlmEvalApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmEvalApplication.class, args);
    }
}
