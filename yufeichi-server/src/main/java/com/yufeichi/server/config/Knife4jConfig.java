package com.yufeichi.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI yufeichiOpenApi() {
        SecurityScheme bearerScheme = new SecurityScheme()
                .name("Authorization")
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");

        return new OpenAPI()
                .info(new Info()
                        .title("Yufeichi Platform API")
                        .description("个人博客、项目展示与后台管理平台接口文档")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Yufeichi")
                                .url("https://yufeichi.com")))
                .components(new Components()
                        .addSecuritySchemes("BearerAuth", bearerScheme));
    }
}
