package com.taskpilot.executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TaskPilotExecutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskPilotExecutorApplication.class, args);
    }
}