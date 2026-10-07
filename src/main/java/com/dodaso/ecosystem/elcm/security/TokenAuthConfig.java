package com.dodaso.ecosystem.elcm.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Decoder for SSO access tokens. Keys are fetched from the SSO's JWKS endpoint on first use and
 * cached; an unknown key id triggers a refresh, so a rotated signing key is picked up. The SSO
 * certificate must be trusted by this JVM (the same truststore the services already use).
 */
@Configuration
public class TokenAuthConfig {

  @Bean
  public JwtDecoder ssoJwtDecoder(@Value("${dodaso.instance.auth-issuer-uri}") String issuer) {
    String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(base + "/oauth2/jwks").build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer)));
    return decoder;
  }
}
