package com.flag.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = {"com.flag.ingest", "com.flag.common"})
@EnableAsync
public class IngestApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestApplication.class, args);
    }
}