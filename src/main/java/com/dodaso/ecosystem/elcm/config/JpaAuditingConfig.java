package com.dodaso.ecosystem.elcm.config;

import com.dodaso.ecosystem.elcm.audit.HeaderBasedAuditorAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditDateTimeAware")
public class JpaAuditingConfig {
  @Bean("auditorAware")
  public AuditorAware<String> auditorAware() {
    return new HeaderBasedAuditorAware();
  }
}