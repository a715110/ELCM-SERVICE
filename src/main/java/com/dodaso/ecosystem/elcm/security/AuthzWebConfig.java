package com.dodaso.ecosystem.elcm.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link PermissionInterceptor} for the REST API. */
@Configuration
@RequiredArgsConstructor
public class AuthzWebConfig implements WebMvcConfigurer {

  private final PermissionInterceptor permissionInterceptor;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(permissionInterceptor).addPathPatterns("/api/**");
  }
}
