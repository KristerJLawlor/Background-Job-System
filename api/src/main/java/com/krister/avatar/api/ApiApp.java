package com.krister.avatar.api;

//Import Spring Boot Dependencies
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync //Enable asynchronous processing for background jobs
public class ApiApp {
    public static void main(String[] args) {
        //Start running Spring Boot Application
        SpringApplication.run(ApiApp.class, args);
    }
}
