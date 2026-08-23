package com.dodaso.ecosystem.elcm.audit;

import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AuditDateTimeAware implements DateTimeProvider {

//  @Override
//  public Optional<TemporalAccessor> getNow() {
//    try {
//      RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
//      if (requestAttributes instanceof ServletRequestAttributes servletRequestAttributes) {
//        HttpServletRequest request = servletRequestAttributes.getRequest();
//
//        // Get user timezone from header
//        String userTimezone = request.getHeader("X-User-Timezone");
//        if (userTimezone != null && !userTimezone.trim().isEmpty()) {
//          log.info("Using timezone from header: {}", userTimezone);
//
//          ZoneId zoneId = ZoneId.of(userTimezone);
//          ZonedDateTime zonedDateTime = ZonedDateTime.now(zoneId);
//
//          // Log the actual times for debugging
//          log.info("Current time in {}: {}", userTimezone, zonedDateTime);
//          log.info("Converting to Instant (UTC): {}", zonedDateTime.toInstant());
//
//          return Optional.of(zonedDateTime.toInstant());
//        }
//      }
//
//      log.warn("No request context or timezone found, using system default");
//      ZonedDateTime systemTime = ZonedDateTime.now(ZoneId.systemDefault());
//      log.info("System default time: {}", systemTime);
//
//      return Optional.of(systemTime.toInstant());
//
//    } catch (Exception e) {
//      log.error("Error getting current time, falling back to system default", e);
//      return Optional.of(ZonedDateTime.now(ZoneId.systemDefault()).toInstant());
//    }
//  }

  @Override
  public Optional<TemporalAccessor> getNow() {
    return Optional.of(Instant.now()); // Always UTC — no header needed
  }
}