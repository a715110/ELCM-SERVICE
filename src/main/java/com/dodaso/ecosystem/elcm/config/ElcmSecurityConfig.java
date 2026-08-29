package com.dodaso.ecosystem.elcm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import com.dodaso.ecosystem.common.config.AbstractBaseSecurityConfig;

@Configuration
public class ElcmSecurityConfig extends AbstractBaseSecurityConfig{
  // @Autowired
  // private JwtAuthEntryPoint unauthorizedHandler;

  // @Autowired
  // JwtAuthTokenFilter jwtAuthTokenFilter;

  @Value("${spring.profiles.active}")
  private String activeProfile;

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