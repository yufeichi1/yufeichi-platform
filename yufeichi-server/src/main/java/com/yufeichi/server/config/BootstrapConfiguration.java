package com.yufeichi.server.config;

import com.yufeichi.server.security.BootstrapService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@Profile("bootstrap")
@EnableAutoConfiguration
@Import({BootstrapService.class, ProductionSafetyConfiguration.class})
public class BootstrapConfiguration {
    @Bean PasswordEncoder bootstrapEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean ApplicationRunner initializeAdministrator(BootstrapService service, Environment env) {
        return args -> service.initialize(env.getRequiredProperty("BOOTSTRAP_USERNAME"), env.getRequiredProperty("BOOTSTRAP_PASSWORD"));
    }
}
