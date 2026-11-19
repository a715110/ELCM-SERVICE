package com.dodaso.ecosystem.elcm.criticaldate;

import java.time.LocalDate;

/**
 * ADDED 2026-11-16 -- the date arithmetic and wording of critical date tasks, with no database and
 * no Spring, so it can be tested alone. A critical date is the date written in the contract (for
 * example the expiration date). The task is due on that date, or earlier by the notice period when
 * the rule has one, and it is created leadDays before it is due.
 */
public final class CriticalDateRules {

  /** What to do with one date today. */
  public enum Decision {
    /** The task is not due to be created yet. */
    NOT_YET,
    /** Create the task now. */
    CREATE,
    /** The due date has already passed; no task is made for a past date. */
    PAST
  }

  private CriticalDateRules() {
  }

  /** The due date: the contract date less the notice days (none, or a negative number, means 0). */
  public static LocalDate dueDate(final LocalDate eventDate, final Integer noticeDays) {
    final int notice = noticeDays == null ? 0 : Math.max(0, noticeDays);
    return eventDate.minusDays(notice);
  }

  /** The first day the task may be created: leadDays before the due date. */
  public static LocalDate createOn(final LocalDate dueDate, final int leadDays) {
    return dueDate.minusDays(Math.max(0, leadDays));
  }

  /** Decides what to do with a date on a given day. The due day itself still creates the task. */
  public static Decision decide(final LocalDate today, final LocalDate dueDate,
      final int leadDays) {
    if (dueDate.isBefore(today)) {
      return Decision.PAST;
    }
    if (today.isBefore(createOn(dueDate, leadDays))) {
      return Decision.NOT_YET;
    }
    return Decision.CREATE;
  }

  /** Where one date stands, for the Critical Dates screen. */
  public enum Status {
    /** Too early: the task will be created later. */
    WAITING,
    /** Inside the lead time and no task yet: it is made at the next check. */
    READY,
    /** A task was requested in ECWS and the due date has not passed. */
    TASK_REQUESTED,
    /** A task was requested and the due date has passed. */
    OVERDUE,
    /** The due date has passed and no task was ever made. */
    PAST
  }

  /** The status of a date on a given day; hasTask is true when an OPEN task exists for it. */
  public static Status status(final LocalDate today, final LocalDate dueDate, final int leadDays,
      final boolean hasTask) {
    if (hasTask) {
      return dueDate.isBefore(today) ? Status.OVERDUE : Status.TASK_REQUESTED;
    }
    return switch (decide(today, dueDate, leadDays)) {
      case PAST -> Status.PAST;
      case NOT_YET -> Status.WAITING;
      case CREATE -> Status.READY;
    };
  }

  /** The ECWS section of one date. At most 50 characters, so it fits ECWS ui_section_id. */
  public static String sectionKey(final long ruleId, final long termId) {
    return "CD-" + ruleId + "-" + termId;
  }

  /** The task summary, for example "Lease expiration: R-001 (Acme) on 2027-03-31". */
  public static String summary(final String label, final String recordCode,
      final String counterparty, final LocalDate dueDate) {
    final StringBuilder text = new StringBuilder(label).append(": ").append(nz(recordCode));
    if (counterparty != null && !counterparty.isBlank()) {
      text.append(" (").append(counterparty.trim()).append(")");
    }
    text.append(" on ").append(dueDate);
    return cut(text.toString(), 200);
  }

  /** The task description: what the date is, where it comes from, and where to look. */
  public static String description(final String label, final String recordCode,
      final String counterparty, final LocalDate eventDate, final Integer noticeDays,
      final LocalDate dueDate, final String openLink) {
    final StringBuilder text = new StringBuilder();
    text.append(label).append(" date of contract record ").append(nz(recordCode));
    if (counterparty != null && !counterparty.isBlank()) {
      text.append(" with ").append(counterparty.trim());
    }
    text.append(" is ").append(eventDate).append('.');
    if (noticeDays != null && noticeDays > 0) {
      text.append(" The notice period is ").append(noticeDays).append(" days, so action is due by ")
          .append(dueDate).append('.');
    }
    text.append(" Decide what to do (renew, renegotiate, terminate or let it run) and close this"
        + " task.");
    if (openLink != null && !openLink.isBlank()) {
      text.append(" Open: ").append(openLink);
    }
    return cut(text.toString(), 500);
  }

  private static String nz(final String s) {
    return s == null ? "" : s;
  }

  private static String cut(final String s, final int max) {
    return s.length() <= max ? s : s.substring(0, max - 3) + "...";
  }
}
