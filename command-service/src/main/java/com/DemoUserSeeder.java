package com;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "fleetmind.auth.seed-demo-users", havingValue = "true")
public class DemoUserSeeder implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Value("${fleetmind.auth.demo-password:demo123}")
    private String demoPassword;

    public DemoUserSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        seed("dispatcher@acme.com", "acme", "DISPATCHER", encoder);
        seed("admin@acme.com", "acme", "ADMIN,DISPATCHER", encoder);
        seed("viewer@acme.com", "acme", "VIEWER", encoder);
        seed("dispatcher@globex.com", "globex", "DISPATCHER", encoder);
        seed("svc-simulator@fleetmind.internal", "acme", "SERVICE", encoder);
    }

    private void seed(String email, String tenant, String roles, BCryptPasswordEncoder encoder) {
        jdbc.update("""
                INSERT INTO app_users (tenant_id, email, password_hash, roles)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (email) DO NOTHING
                """, tenant, email, encoder.encode(demoPassword), roles);
    }
}
