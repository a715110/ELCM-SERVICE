package com.dodaso.ecosystem.elcm.service.handler;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TimezoneContextHelper {
  private static final ThreadLocal<String> userTimezone = new ThreadLocal<>();

  public static void setUserTimezone(String timezone) {
    userTimezone.set(timezone);
  }

  public static String getUserTimezone() {
    String timezone = userTimezone.get();
    return timezone != null ? timezone : "UTC";
  }

  public static void clear() {
    userTimezone.remove();
  }

  // Convert Instant to user's timezone
  public static Instant convertToUserTimezone(Instant instant) {
    if (instant == null) return null;

    try {
      ZoneId userZone = ZoneId.of(getUserTimezone());
      return instant.atZone(userZone).toInstant();
    } catch (Exception e) {
      // Fallback to system default
      return instant.atZone(ZoneId.systemDefault()).toInstant();
    }
  }

  // Format Instant in user's timezone
  public static String formatInUserTimezone(Instant instant, String pattern) {
    if (instant == null) return null;

    try {
      ZoneId userZone = ZoneId.of(getUserTimezone());
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
      return instant.atZone(userZone).format(formatter);
    } catch (Exception e) {
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
      return instant.atZone(ZoneId.systemDefault()).format(formatter);
    }
  }
}