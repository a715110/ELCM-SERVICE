package com.dodaso.ecosystem.elcm.criticaldate;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADDED 2026-11-16 -- settings under elcm.critical-dates. Off until enabled is true. The ECWS
 * integration (elcm.ecws.enabled) must be on as well, because the tasks are sent through it.
 */
@ConfigurationProperties(prefix = "elcm.critical-dates")
@Getter
@Setter
public class CriticalDateProperties {

  /** Master switch. */
  private boolean enabled = false;

  /** When the daily check runs (Spring cron, six fields). */
  private String cron = "0 0 6 * * *";

  /** Time zone of the cron and of "today" when dates are compared. */
  private String zone = "America/New_York";

  /** True to run one check when the service has started (useful after the first deployment). */
  private boolean runOnStartup = false;
}
