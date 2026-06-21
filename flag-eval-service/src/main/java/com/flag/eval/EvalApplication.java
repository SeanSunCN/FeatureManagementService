package com.flag.eval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.flag.eval", "com.flag.common"})
public class EvalApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvalApplication.class, args);
    }
}