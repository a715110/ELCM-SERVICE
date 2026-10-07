package com.dodaso.ecosystem.elcm.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import com.dodaso.ecosystem.baseline.common.config.AbstractBaseSecurityConfig;
import com.dodaso.ecosystem.elcm.security.AccessTokenFilter;

@Configuration
public class ElcmSecurityConfig extends AbstractBaseSecurityConfig{
  // @Autowired
  // private JwtAuthEntryPoint unauthorizedHandler;

  // @Autowired
  // JwtAuthTokenFilter jwtAuthTokenFilter;

  @Value("${spring.profiles.active}")
  private String activeProfile;

  @Autowired
  private JwtDecoder ssoJwtDecoder;

  /**
   * Rollout switch (env DODASO_AUTHZ_TOKEN_REQUIRED). false: a request without an access token is
   * still served (identity from X-User-Context) and logged. true: it gets 401. A token that is
   * present but invalid is rejected either way.
   */
  @Value("${dodaso.authz.token.required:false}")
  private boolean tokenRequired;

  @Override
  protected void configureAdditionalSecurity(HttpSecurity http) throws Exception {
    http.addFilterBefore(new AccessTokenFilter(ssoJwtDecoder, tokenRequired), AuthorizationFilter.class);
  }

  @Override
  protected void configureAuthorizationRules(HttpSecurity http) throws Exception {
    // http.authorizeHttpRequests(auth -> auth
    //     .requestMatchers("/api/ecws/public/**").permitAll()
    //     .requestMatchers("/api/ecws/admin/**").hasRole("ECWS_ADMIN")
    //     .anyRequest().authenticated()
    // );
    http.authorizeHttpRequests(authorize -> authorize.requestMatchers("/**").permitAll());
  }
}