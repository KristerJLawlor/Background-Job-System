package com.krister.avatar.api;

//Import Spring Boot Dependencies
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling // required for @Scheduled eviction in ImageJobService
public class ApiApp {
    public static void main(String[] args) {
        //Start running Spring Boot Application
        SpringApplication.run(ApiApp.class, args);
    }
}
