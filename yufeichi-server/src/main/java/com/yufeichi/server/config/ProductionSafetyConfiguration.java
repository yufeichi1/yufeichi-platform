package com.yufeichi.server.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionSafetyConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    InitializingBean productionSafety(Environment env, JdbcTemplate jdbc) {
        return () -> {
            if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("dev", "test")))
                throw new IllegalStateException("Production cannot be combined with dev/test profiles");
            for (String name : new String[]{"DB_HOST", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "REDIS_HOST", "REDIS_PASSWORD", "JWT_SECRET", "UPLOAD_PATH"}) {
                if (env.getProperty(name, "").isBlank()) throw new IllegalStateException("Required production variable missing: " + name);
            }
            String secret = env.getRequiredProperty("JWT_SECRET");
            if (secret.getBytes(StandardCharsets.UTF_8).length < 64 || secret.contains("test") || secret.contains("dev-secret") || secret.contains("change-this"))
                throw new IllegalStateException("Production JWT_SECRET must be independently generated (at least 64 bytes)");
            if (env.getRequiredProperty("DB_USERNAME").equalsIgnoreCase("root")) throw new IllegalStateException("Production must use a dedicated database account");
            if (!Path.of(env.getRequiredProperty("UPLOAD_PATH")).isAbsolute()) throw new IllegalStateException("UPLOAD_PATH must be absolute");
            int minutes = env.getProperty("jwt.expire-minutes", Integer.class, 45);
            if (minutes < 30 || minutes > 60) throw new IllegalStateException("JWT lifetime must be 30-60 minutes");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=1 AND username='admin' AND status=1", Integer.class) != 0)
                throw new IllegalStateException("Public seed account must remain disabled");
            if (!Arrays.asList(env.getActiveProfiles()).contains("bootstrap") && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT completed FROM sys_bootstrap_state WHERE id=1", Boolean.class)))
                throw new IllegalStateException("Run the offline --bootstrap command before starting production");
        };
    }
}
