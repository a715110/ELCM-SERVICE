package com.dodaso.ecosystem.elcm.criticaldate;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADDED 2026-11-21 -- the plain checks of the critical date rule screen, kept free of Spring and
 * the database so they are easy to test. Each check gives the message to show, or null when the
 * input is fine. Text is cleaned by the normalize methods before it is checked or saved.
 */
public final class CriticalDateRuleChecks {

  public static final int MAX_RULES = 50;
  public static final int LABEL_MAX = 100;
  public static final int LEAD_DAYS_MAX = 1095;

  /** The ECWS priorities, as stored. */
  private static final List<String> PRIORITIES = List.of("Low", "Medium", "High");
  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");

  private CriticalDateRuleChecks() {
  }

  public static List<String> priorities() {
    return PRIORITIES;
  }

  /** Upper case, spaces turned to underscores; null stays null. */
  public static String normalizeCode(final String code) {
    return code == null ? null : code.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
  }

  /** Trimmed text; blank becomes null. */
  public static String blankToNull(final String text) {
    return text == null || text.isBlank() ? null : text.trim();
  }

  /** The stored spelling of a priority (any letter case is accepted), or null when unknown. */
  public static String normalizePriority(final String priority) {
    if (priority == null) {
      return null;
    }
    for (final String known : PRIORITIES) {
      if (known.equalsIgnoreCase(priority.trim())) {
        return known;
      }
    }
    return null;
  }

  /** Message when the (normalized) code cannot be used, else null. */
  public static String checkCode(final String code) {
    if (code == null || code.isBlank()) {
      return "Give the rule a short code, such as RENEWAL_NOTICE.";
    }
    if (!CODE.matcher(code).matches()) {
      return "The code starts with a letter and uses only capital letters, digits and "
          + "underscores, 2 to 40 characters.";
    }
    return null;
  }

  public static String checkLabel(final String label) {
    if (label == null || label.isBlank()) {
      return "Give the date a name, such as Lease expiration.";
    }
    if (label.trim().length() > LABEL_MAX) {
      return "The name can have at most " + LABEL_MAX + " characters.";
    }
    return null;
  }

  public static String checkLeadDays(final Integer leadDays) {
    if (leadDays == null) {
      return "Say how many days before the due date the task is made.";
    }
    if (leadDays < 0 || leadDays > LEAD_DAYS_MAX) {
      return "Days ahead must be from 0 to " + LEAD_DAYS_MAX + ".";
    }
    return null;
  }

  public static String checkPriority(final String priority) {
    return normalizePriority(priority) == null ? "Pick Low, Medium or High." : null;
  }

  /**
   * Message when the contract field or the notice field cannot be used, else null. The contract
   * field must be a DATE field and the notice field a NUMBER field of a published template.
   */
  public static String checkFields(final String fieldKey, final String noticeFieldKey,
      final Set<String> dateKeys, final Set<String> numberKeys) {
    if (fieldKey == null || fieldKey.isBlank()) {
      return "Pick the contract field that holds the date.";
    }
    if (!dateKeys.contains(fieldKey)) {
      return "The contract field must be a date field of a published template.";
    }
    if (noticeFieldKey != null && !noticeFieldKey.isBlank()
        && !numberKeys.contains(noticeFieldKey)) {
      return "The notice field must be a number field of a published template.";
    }
    return null;
  }

  /**
   * True when a change moves the date the rule watches. The tasks already open for the rule then
   * stop standing and the next check closes them (see CriticalDateService).
   */
  public static boolean changesWatchedDate(final String oldField, final String oldNotice,
      final String newField, final String newNotice) {
    return !same(oldField, newField) || !same(blankToNull(oldNotice), blankToNull(newNotice));
  }

  private static boolean same(final String a, final String b) {
    return a == null ? b == null : a.equals(b);
  }
}
