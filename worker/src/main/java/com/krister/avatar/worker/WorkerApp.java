package com.krister.avatar.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.krister.avatar.worker", "com.krister.avatar.shared"})
public class WorkerApp {
    public static void main(String[] args) {
        SpringApplication.run(WorkerApp.class, args);
    }
}
