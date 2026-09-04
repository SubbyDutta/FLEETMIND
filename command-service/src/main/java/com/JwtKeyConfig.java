package com;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Configuration
public class JwtKeyConfig {

    @Value("${fleetmind.auth.jwk-file:${user.home}/.fleetmind/jwk.json}")
    private String jwkFile;

    @Bean
    public RSAKey rsaKey() throws Exception {
        RSAKey generated = new RSAKeyGenerator(2048)
                .keyID(UUID.randomUUID().toString())
                .generate();
        if (jwkFile == null || jwkFile.isBlank()) {
            return generated;
        }
        Path path = Path.of(jwkFile);
        if (Files.exists(path)) {
            return RSAKey.parse(Files.readString(path));
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, generated.toJSONString());
        return generated;
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey rsaKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey) throws Exception {
        return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
    }
}
