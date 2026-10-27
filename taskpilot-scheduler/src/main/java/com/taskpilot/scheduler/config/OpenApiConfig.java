package com.taskpilot.scheduler.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI taskPilotOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("TaskPilot Scheduler API")
                        .version("v1")
                        .description("分布式任务调度平台 - 调度中心接口"));
    }
}
