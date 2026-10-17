package com.dodaso.ecosystem.elcm.integration.ecws;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ADDED 2026-10-16 -- binds the elcm.ecws.* properties. Scheduling is already switched on in
 * ExtractionConfig.
 */
@Configuration
@EnableConfigurationProperties(EcwsIntegrationProperties.class)
public class EcwsIntegrationConfig {
}
