package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.integration.ecws.EcwsIntegrationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADDED 2026-11-07 -- checks that a package can reach a reviewer and an approver, so it cannot
 * stall with a task nobody can take.
 *
 * <p>The roles are the IAMS roles named in elcm.ecws.reviewer-role and elcm.ecws.approver-role.
 * With the two-person rule on (elcm.review.require-different-reviewer, default true) the reviewer
 * must not be the submitter, and the approver must be neither the submitter nor the reviewer. With
 * the rule off, one person may fill all three places. elcm.ecws.validate-roles=false switches
 * these checks off.
 *
 * <p>A failed check answers 412 (precondition failed), which the screens show as a plain message.
 */
@Component
@RequiredArgsConstructor
public class ReviewRoleRules {

  private final EcwsIntegrationProperties props;
  private final AssigneeDirectoryLookupService directory;

  @Value("${elcm.review.require-different-reviewer:true}")
  private boolean separationOn;

  /** People who may review a package of this submitter: the Reviewer role, not the submitter. */
  public List<UserDirectoryDTO> reviewerCandidates(final String submitter) {
    return holders(props.getReviewerRole(), submitter);
  }

  /** People who may approve: the Approver role, not the submitter and not the reviewer. */
  public List<UserDirectoryDTO> approverCandidates(final String submitter, final String reviewer) {
    return holders(props.getApproverRole(), submitter, reviewer);
  }

  /**
   * Before a package is submitted: its assignee must be able to review it, and someone must be
   * able to approve it afterwards. 412 with the reason otherwise.
   */
  public void checkSubmit(final String submitter, final String assigneeId) {
    if (!props.isValidateRoles()) {
      return;
    }
    final Optional<UserDirectoryDTO> assignee = directory.findByAssigneeId(assigneeId);
    final String reviewer = assignee.map(UserDirectoryDTO::getLoginId).orElse(null);
    if (reviewer == null) {
      throw refused("The package reviewer is not a known user. Assign the package to a person who"
          + " holds the " + props.getReviewerRole() + " role.");
    }
    if (separationOn && same(reviewer, submitter)) {
      throw refused("The reviewer cannot be the person who submits the package. Assign it to"
          + " another " + props.getReviewerRole() + ".");
    }
    if (reviewerCandidates(submitter).stream().noneMatch(u -> same(u.getLoginId(), reviewer))) {
      throw refused(nameOf(assignee.get()) + " does not hold the " + props.getReviewerRole()
          + " role. Assign the package to a person who does.");
    }
    if (approverCandidates(submitter, reviewer).isEmpty()) {
      throw refused("No " + props.getApproverRole() + " is available for this package. At least one"
          + " person with the " + props.getApproverRole() + " role other than the submitter and the"
          + " reviewer is needed. Ask an administrator.");
    }
  }

  /** When a review is completed: someone must be left who may approve it. 412 otherwise. */
  public void checkReviewCompleted(final Submission submission, final String reviewer) {
    if (!props.isValidateRoles()) {
      return;
    }
    if (approverCandidates(submission.getSubmittedBy(), reviewer).isEmpty()) {
      throw refused("No " + props.getApproverRole() + " is available for this package. At least one"
          + " person with the " + props.getApproverRole() + " role other than the submitter and the"
          + " reviewer is needed. Ask an administrator.");
    }
  }

  /**
   * A new approver for a package that waits for approval. Returns the person's login id as the
   * directory spells it. 412 when the person is not an eligible approver.
   */
  public String checkNewApprover(final Submission submission, final String newApprover) {
    return checkApprover(submission.getSubmittedBy(), submission.getReviewedBy(), newApprover);
  }

  /**
   * An approver picked by the reviewer while completing the review (the reviewer is the caller, so
   * the submission does not name them yet). Same rule as {@link #checkNewApprover}.
   */
  public String checkApprover(final String submitter, final String reviewer, final String chosen) {
    final Optional<UserDirectoryDTO> found = directory.findByAssigneeId(chosen);
    final String login = found.map(UserDirectoryDTO::getLoginId).orElse(null);
    if (login == null || approverCandidates(submitter, reviewer)
        .stream().noneMatch(u -> same(u.getLoginId(), login))) {
      throw refused("That person cannot be the approver. They must hold the "
          + props.getApproverRole() + " role and must not be the submitter or the reviewer.");
    }
    return login;
  }

  private List<UserDirectoryDTO> holders(final String role, final String... excluded) {
    final List<UserDirectoryDTO> result = new ArrayList<>();
    for (final UserDirectoryDTO user : directory.findAllWithRole(role)) {
      boolean skip = false;
      if (separationOn) {
        for (final String other : excluded) {
          skip = skip || same(user.getLoginId(), other);
        }
      }
      if (!skip) {
        result.add(user);
      }
    }
    return result;
  }

  private static boolean same(final String a, final String b) {
    return a != null && b != null
        && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
  }

  private static String nameOf(final UserDirectoryDTO user) {
    final String name = user.getDisplayName();
    return name == null || name.isBlank() ? user.getLoginId() : name;
  }

  private static ResponseStatusException refused(final String message) {
    return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, message);
  }
}
