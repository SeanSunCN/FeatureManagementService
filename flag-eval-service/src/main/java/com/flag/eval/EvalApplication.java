package com.flag.eval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication(scanBasePackages = {"com.flag.eval", "com.flag.common"})
public class EvalApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(EvalApplication.class)
                .web(WebApplicationType.REACTIVE) // 👈 强制锁死响应式容器，拒绝 Tomcat 启动
                .run(args);
    }
}