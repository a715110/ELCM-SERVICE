package com.dodaso.ecosystem.elcm.criticaldate;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** ADDED 2026-11-16 -- registers CriticalDateProperties. */
@Configuration
@EnableConfigurationProperties(CriticalDateProperties.class)
public class CriticalDateConfig {
}
