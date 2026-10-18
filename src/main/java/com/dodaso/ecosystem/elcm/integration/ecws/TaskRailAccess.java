package com.dodaso.ecosystem.elcm.integration.ecws;

import java.util.Locale;

/**
 * ADDED 2026-10-18 -- who may see a Review or Approval task in the Task Rail, and why. Pure rules,
 * no database, so they can be tested alone.
 *
 * <p>A person sees a task when any of these holds, checked in this order (the first match names the
 * relation shown on the line):
 *
 * <ol>
 *   <li>It is assigned to them (ASSIGNED).
 *   <li>They submitted the package (SUBMITTER), for both Review and Approval.
 *   <li>They completed the review of the package (REVIEWER), for both Review and Approval.
 *   <li>They are an administrator (WATCHING).
 *   <li>The task is open and has nobody assigned, and they hold the role that does the step: the
 *       reviewer role for Review, the approver role for Approval (POOL).
 * </ol>
 *
 * Closed tasks never use the last rule: a closed task was done by someone, so only the people
 * above see it afterwards. Login ids are compared ignoring case.
 */
public final class TaskRailAccess {

  public static final String ASSIGNED = "ASSIGNED";
  public static final String SUBMITTER = "SUBMITTER";
  public static final String REVIEWER = "REVIEWER";
  public static final String POOL = "POOL";
  public static final String WATCHING = "WATCHING";

  private TaskRailAccess() {
  }

  /** The caller: login id plus the three facts the rules need. */
  public record Caller(String login, boolean admin, boolean hasReviewerRole,
                       boolean hasApproverRole) {
  }

  /**
   * The relation under which the caller sees the task, or null when they may not see it.
   *
   * @param open true for an open task, false for a closed one
   * @param event REVIEW or APPROVAL
   * @param assignee the task's assignee login id, may be blank
   * @param submitter the package's submitter, may be null
   * @param reviewer the person who completed the package's review, may be null
   */
  public static String relation(final Caller caller, final boolean open, final String event,
      final String assignee, final String submitter, final String reviewer) {
    if (same(assignee, caller.login())) {
      return ASSIGNED;
    }
    if (same(submitter, caller.login())) {
      return SUBMITTER;
    }
    if (same(reviewer, caller.login())) {
      return REVIEWER;
    }
    if (caller.admin()) {
      return WATCHING;
    }
    if (open && (assignee == null || assignee.isBlank())) {
      if (EcwsTaskPublisher.REVIEW.equals(event) && caller.hasReviewerRole()) {
        return POOL;
      }
      if (EcwsTaskPublisher.APPROVAL.equals(event) && caller.hasApproverRole()) {
        return POOL;
      }
    }
    return null;
  }

  /** True for the relations that mean "this is mine": the Mine filter keeps only these. */
  public static boolean isMine(final String relation) {
    return ASSIGNED.equals(relation) || SUBMITTER.equals(relation) || REVIEWER.equals(relation);
  }

  /** Login ids compared ignoring case and surrounding blanks; blank never matches. */
  static boolean same(final String a, final String b) {
    if (a == null || b == null || a.isBlank() || b.isBlank()) {
      return false;
    }
    return a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
  }
}
