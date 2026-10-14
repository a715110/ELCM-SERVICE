package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Two-person rule (ADDED 2026-10-12): the person who submitted a package cannot review it. It is
 * checked on the actions that vouch for the values: saving or confirming a field, confirming all,
 * completing the review and applying. Decline and Reopen are not blocked, since they only send work
 * back. Login ids are compared ignoring case. When the submission has no recorded submitter, or the
 * caller is unknown, nothing is blocked. Setting elcm.review.require-different-reviewer=false
 * switches the rule off (single-user demos).
 */
@Component
public class ReviewerSeparation {

  public static final String MESSAGE = "You submitted this package, so someone else must review it.";

  private final boolean enabled;

  public ReviewerSeparation(
      @Value("${elcm.review.require-different-reviewer:true}") final boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Throws 403 when the caller is the one who submitted the submission.
   */
  public void check(final Submission submission, final String caller) {
    if (blocks(submission, caller)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, MESSAGE);
    }
  }

  public static final String APPROVER_MESSAGE =
      "You submitted or reviewed this package, so another person must approve it.";

  /**
   * Throws 403 when the caller submitted the submission or completed its review.
   */
  public void checkApprover(final Submission submission, final String caller) {
    if (blocksApprover(submission, caller)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, APPROVER_MESSAGE);
    }
  }

  /**
   * True when the rule is on and the caller submitted the submission or completed its review.
   */
  public boolean blocksApprover(final Submission submission, final String caller) {
    if (!enabled || submission == null || caller == null || caller.isBlank()) {
      return false;
    }
    return same(submission.getSubmittedBy(), caller) || same(submission.getReviewedBy(), caller);
  }

  private static boolean same(final String a, final String b) {
    return a != null && a.trim().equalsIgnoreCase(b.trim());
  }

  /**
   * True when the rule is on and the caller is the one who submitted the submission.
   */
  public boolean blocks(final Submission submission, final String caller) {
    if (!enabled || submission == null || caller == null || caller.isBlank()) {
      return false;
    }
    final String submitter = submission.getSubmittedBy();
    return submitter != null && submitter.trim().equalsIgnoreCase(caller.trim());
  }
}