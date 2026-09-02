package com;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtAuthTest {

    static RSAKey key;
    static JwtEncoder encoder;
    static JwtDecoder decoder;

    @BeforeAll
    static void keys() throws Exception {
        JwtKeyConfig config = new JwtKeyConfig();
        key = config.rsaKey();
        encoder = config.jwtEncoder(key);
        decoder = config.jwtDecoder(key);
    }

    private static String mint(String subject, String tenant, List<String> roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("fleetmind")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("tenant", tenant)
                .claim("roles", roles)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    void mintedTokenRoundTrips_withTenantAndRoles() {
        String token = mint("dispatcher@acme.com", "acme", List.of("DISPATCHER"));
        Jwt jwt = decoder.decode(token);
        assertEquals("dispatcher@acme.com", jwt.getSubject());
        assertEquals("acme", jwt.getClaimAsString("tenant"));
        assertEquals(List.of("DISPATCHER"), jwt.getClaimAsStringList("roles"));
        assertEquals("fleetmind", jwt.getClaimAsString("iss"));
    }

    @Test
    void tamperedPayload_isRejected() {
        String token = mint("dispatcher@acme.com", "acme", List.of("DISPATCHER"));
        String[] parts = token.split("\\.");
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 4) + "AAAA";
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];
        assertThrows(JwtException.class, () -> decoder.decode(tampered));
    }

    @Test
    void tokenSignedByForeignKey_isRejected() throws Exception {
        JwtKeyConfig other = new JwtKeyConfig();
        RSAKey foreignKey = other.rsaKey();
        JwtEncoder foreignEncoder = other.jwtEncoder(foreignKey);
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("fleetmind")
                .subject("attacker@evil.com")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("tenant", "acme")
                .claim("roles", List.of("ADMIN"))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(foreignKey.getKeyID()).build();
        String forged = foreignEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        assertThrows(JwtException.class, () -> decoder.decode(forged));
    }

    @Test
    void expiredToken_isRejected() {
        Instant past = Instant.now().minusSeconds(7200);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("fleetmind")
                .subject("dispatcher@acme.com")
                .issuedAt(past)
                .expiresAt(past.plusSeconds(60))
                .claim("tenant", "acme")
                .claim("roles", List.of("DISPATCHER"))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        String expired = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        assertThrows(JwtException.class, () -> decoder.decode(expired));
    }
}
