package com.dodaso.ecosystem.elcm.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "headerBasedAuditorAware", dateTimeProviderRef = "auditDateTimeAware")
public class JpaAuditingConfig {}
