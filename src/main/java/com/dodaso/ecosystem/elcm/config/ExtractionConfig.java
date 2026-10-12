package com.dodaso.ecosystem.elcm.config;

import com.dodaso.ecosystem.elcm.service.extraction.ExtractionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduler (the extraction worker is the first scheduled job in this service) and
 * binds the elcm.extraction.* properties.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ExtractionProperties.class)
public class ExtractionConfig {
}
