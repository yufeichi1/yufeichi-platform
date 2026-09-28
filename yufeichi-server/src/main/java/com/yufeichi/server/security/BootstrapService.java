package com.yufeichi.server.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;

/** Only imported by the offline bootstrap application (never exposed over HTTP). */
@RequiredArgsConstructor
public class BootstrapService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    @Transactional
    public void initialize(String username, String password) {
        if (username == null || !username.matches("[a-z][a-z0-9_-]{2,49}") || username.equals("admin"))
            throw new IllegalArgumentException("BOOTSTRAP_USERNAME must be a new lowercase account (3-50 characters)");
        if (password == null || password.length() < 16 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("BOOTSTRAP_PASSWORD must contain at least 16 characters and at most 72 UTF-8 bytes");
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT completed FROM sys_bootstrap_state WHERE id=1 FOR UPDATE", Boolean.class)))
            throw new IllegalStateException("Bootstrap has already completed");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, username) != 0)
            throw new IllegalStateException("Bootstrap account already exists");
        Long role = jdbc.queryForObject("SELECT id FROM sys_role WHERE role_code='super_admin' AND status=1 AND deleted=0", Long.class);
        jdbc.update("INSERT INTO sys_user(username,password,nickname,status) VALUES(?,?,?,1)", username, encoder.encode(password), "Administrator");
        Long id = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", id, role);
        jdbc.update("UPDATE sys_bootstrap_state SET completed=TRUE, completed_at=CURRENT_TIMESTAMP WHERE id=1");
    }
}
