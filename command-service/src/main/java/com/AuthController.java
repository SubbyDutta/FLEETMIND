package com;

import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final JwtEncoder encoder;
    private final RSAKey rsaKey;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    @Value("${fleetmind.auth.token-ttl-minutes:60}")
    private long tokenTtlMinutes;

    public AuthController(JdbcTemplate jdbc, JwtEncoder encoder, RSAKey rsaKey) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.rsaKey = rsaKey;
    }
    public record LoginRequest(String email, String password) {}
    public record LoginResponse(String token, String tokenType, long expiresInSeconds,
                                String tenant, List<String> roles) {}
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req)
    {
        List<Map<String,Object>> rows=jdbc.queryForList(
                "SELECT tenant_id,password_hash,roles FROM app_users WHERE email=?",req.email());

        if (rows.isEmpty() || !passwordEncoder.matches(req.password(), (String) rows.get(0).get("password_hash"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        String tenant=(String) rows.get(0).get("tenant_id");
        List<String> roles = Arrays.asList(((String) rows.get(0).get("roles")).split(","));
        Instant now=Instant.now();
        JwtClaimsSet claims= JwtClaimsSet.builder()
                .issuer("fleetmind")
                .subject(req.email())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(tokenTtlMinutes)))
                .claim("tenant",tenant)
                .claim("roles",roles)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsaKey.getKeyID()).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", tokenTtlMinutes * 60, tenant, roles);
    }
}
