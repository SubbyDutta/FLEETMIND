package com;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class JwksController {

    private final JWKSet jwkSet;

    public JwksController(RSAKey rsaKey) {
        this.jwkSet = new JWKSet(rsaKey.toPublicJWK());
    }

    @GetMapping("/api/auth/jwks")
    public Map<String, Object> jwks() {
        return jwkSet.toJSONObject();
    }
}