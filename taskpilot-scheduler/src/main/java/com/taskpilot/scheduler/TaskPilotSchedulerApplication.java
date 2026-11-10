package com.taskpilot.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TaskPilotSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskPilotSchedulerApplication.class, args);
    }
}