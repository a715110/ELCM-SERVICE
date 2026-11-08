package com.dodaso.ecosystem.elcm.integration.ecws;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.elcm.service.pipeline.AssigneeDirectoryLookupService;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ADDED 2026-10-16 -- chooses who an ECWS task goes to. From everyone holding the role, it drops the
 * people who must not do this step (the submitter, and for approval also the reviewer, when the
 * two-person rule elcm.review.require-different-reviewer is on), then takes the one with the fewest
 * open ELCM tasks; a tie goes to the lower login id. Returns empty when nobody qualifies, and the
 * task is then created unassigned.
 */
@Component
@RequiredArgsConstructor
public class EcwsAssigneePicker {

  private final AssigneeDirectoryLookupService directory;
  private final EcwsTaskOutboxRepository outbox;

  @Value("${elcm.review.require-different-reviewer:true}")
  private boolean separationOn;

  /**
   * @param roleName IAMS role to pick from
   * @param excluded login ids that must not get the task (ignored when the two-person rule is off)
   */
  public Optional<String> pick(final String roleName, final Collection<String> excluded) {
    String best = null;
    long bestLoad = Long.MAX_VALUE;
    for (final UserDirectoryDTO user : directory.findAllWithRole(roleName)) {
      final String login = user.getLoginId();
      if (login == null || login.isBlank() || isExcluded(login, excluded)) {
        continue;
      }
      final long load = outbox.countByActionAndTaskOpenTrueAndAssigneeLoginIgnoreCase(
          EcwsTaskOutbox.CREATE, login);
      if (load < bestLoad) {
        best = login;
        bestLoad = load;
      }
    }
    return Optional.ofNullable(best);
  }

  /**
   * ADDED 2026-11-06 -- like {@link #pick}, but the person in charge of the package comes first.
   * The package assignee gets the task when they hold the role and are not excluded (for review:
   * not the submitter); otherwise the least busy qualifying person is picked as before.
   *
   * @param preferred login id of the package assignee, may be null
   */
  public Optional<String> pickPreferring(
      final String roleName, final String preferred, final Collection<String> excluded) {
    if (preferred != null && !preferred.isBlank() && !isExcluded(preferred, excluded)) {
      for (final UserDirectoryDTO user : directory.findAllWithRole(roleName)) {
        final String login = user.getLoginId();
        if (login != null && preferred.trim().equalsIgnoreCase(login.trim())) {
          return Optional.of(login);
        }
      }
    }
    return pick(roleName, excluded);
  }

  /**
   * ADDED 2026-11-07 -- true when the person holds the role and is not excluded. Used to decide
   * whether a package's new assignee may take over an open review task.
   */
  public boolean qualifies(
      final String roleName, final String login, final Collection<String> excluded) {
    if (login == null || login.isBlank() || isExcluded(login, excluded)) {
      return false;
    }
    for (final UserDirectoryDTO user : directory.findAllWithRole(roleName)) {
      if (user.getLoginId() != null && login.trim().equalsIgnoreCase(user.getLoginId().trim())) {
        return true;
      }
    }
    return false;
  }

  private boolean isExcluded(final String login, final Collection<String> excluded) {
    if (!separationOn || excluded == null) {
      return false;
    }
    final String key = login.trim().toLowerCase(Locale.ROOT);
    for (final String other : excluded) {
      if (other != null && other.trim().toLowerCase(Locale.ROOT).equals(key)) {
        return true;
      }
    }
    return false;
  }
}
