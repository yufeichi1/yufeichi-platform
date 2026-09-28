package com.yufeichi.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionSafetyConfigurationTest {
    MockEnvironment complete() {
        var env = new MockEnvironment(); env.setActiveProfiles("prod");
        for (String name : new String[]{"DB_HOST", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "REDIS_HOST", "REDIS_PASSWORD"})
            env.setProperty(name, "test-value");
        env.setProperty("JWT_SECRET", UUID.randomUUID().toString() + UUID.randomUUID());
        env.setProperty("UPLOAD_PATH", java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
        return env;
    }
    @ParameterizedTest @ValueSource(strings={"DB_HOST","DB_NAME","DB_USERNAME","DB_PASSWORD","REDIS_HOST","REDIS_PASSWORD","JWT_SECRET","UPLOAD_PATH"})
    void missingRequiredSettingStopsInitialization(String name) {
        var env = complete(); env.setProperty(name, "");
        var db = mock(JdbcTemplate.class);
        assertThatThrownBy(() -> new ProductionSafetyConfiguration().productionSafety(env, db).afterPropertiesSet())
                .hasMessage("Required production variable missing: " + name);
        verifyNoInteractions(db);
    }
    @Test void mixedProfilesAreRejected() {
        var env = complete(); env.setActiveProfiles("prod", "dev");
        assertThatThrownBy(() -> new ProductionSafetyConfiguration().productionSafety(env, mock(JdbcTemplate.class)).afterPropertiesSet())
                .hasMessage("Production cannot be combined with dev/test profiles");
    }
    @Test void rejectsRootWeakSecretRelativeUploadsAndInvalidTokenLifetime() {
        for (var invalid : java.util.Map.of("DB_USERNAME", "root", "JWT_SECRET", "short", "UPLOAD_PATH", "relative", "jwt.expire-minutes", "1440").entrySet()) {
            var env = complete(); env.setProperty(invalid.getKey(), invalid.getValue());
            assertThatThrownBy(() -> new ProductionSafetyConfiguration().productionSafety(env, mock(JdbcTemplate.class)).afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
