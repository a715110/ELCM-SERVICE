package com.dodaso.ecosystem.elcm.criticaldate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADDED 2026-11-16 -- the daily check of critical dates. It is also the safety net: a date that was
 * missed because a check after an Apply failed is picked up the next morning.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CriticalDateJob {

  private final CriticalDateProperties props;
  private final CriticalDateService service;

  @Scheduled(cron = "${elcm.critical-dates.cron:0 0 6 * * *}",
      zone = "${elcm.critical-dates.zone:America/New_York}")
  public void daily() {
    if (!props.isEnabled()) {
      return;
    }
    runSafely("daily");
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    if (props.isEnabled() && props.isRunOnStartup()) {
      runSafely("startup");
    }
  }

  private void runSafely(final String why) {
    try {
      final int checked = service.checkAll();
      log.info("CRITICAL-DATES {} check done, {} record(s) looked at", why, checked);
    } catch (final RuntimeException e) {
      log.warn("CRITICAL-DATES {} check failed; it will run again at the next time", why, e);
    }
  }
}
