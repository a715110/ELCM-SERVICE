package com.dodaso.ecosystem.elcm.integration.ecws;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.elcm.security.ElcmRolePermissions;
import com.dodaso.ecosystem.elcm.service.pipeline.AssigneeDirectoryLookupService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * ADDED 2026-10-18 -- the Task Rail (the right-hand panel in the ELCM screens): the Review and
 * Approval tasks the signed-in person may see, as an Open list and a Closed list, plus the badge
 * number. The rows and the access rules come from {@link TaskRailDataService}; this class adds the
 * display names (from the cached IAMS directory, outside any transaction), the due state and the
 * text of each line. A name that cannot be resolved is shown as the stored login id.
 */
@Service
@RequiredArgsConstructor
public class TaskRailService {

  /** Closed tasks are listed for this many days back. */
  public static final int CLOSED_DAYS = 30;

  /** Page size of the Closed list. */
  public static final int CLOSED_PAGE = 20;

  /** Most lines in the Open list. */
  static final int OPEN_MAX = 50;

  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final TaskRailDataService dataService;
  private final EcwsIntegrationProperties props;
  private final AssigneeDirectoryLookupService directory;

  /** Open tasks, overdue first, then by due date; tasks without a due date last. */
  public TaskRailPage open(final TaskRailAccess.Caller caller, final boolean mineOnly) {
    final List<TaskRailDataService.Seen> seen = dataService.open(caller, mineOnly);
    final Map<String, String> names = new HashMap<>();
    final TaskRailPage page = new TaskRailPage();
    final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    seen.stream()
        .map(s -> toItem(s, names, today))
        .sorted((a, b) -> compareOpen(a, b))
        .limit(OPEN_MAX)
        .forEach(page.getItems()::add);
    return page;
  }

  /** One page of closed tasks, newest first. before is the cursor from the previous page. */
  public TaskRailPage closed(final TaskRailAccess.Caller caller, final boolean mineOnly,
      final Long before) {
    final TaskRailDataService.ClosedPage closed = dataService.closed(caller, mineOnly,
        before == null ? Long.MAX_VALUE : before, CLOSED_PAGE, CLOSED_DAYS);
    final Map<String, String> names = new HashMap<>();
    final TaskRailPage page = new TaskRailPage();
    final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    for (final TaskRailDataService.Seen s : closed.items()) {
      page.getItems().add(toItem(s, names, today));
    }
    page.setNextBefore(closed.nextBefore());
    return page;
  }

  public TaskRailCount count(final String login) {
    return new TaskRailCount(dataService.countAssigned(login));
  }

  /** Building the caller from a login id and its IAMS role names. */
  public TaskRailAccess.Caller callerOf(final String login, final List<String> roles) {
    return new TaskRailAccess.Caller(login, ElcmRolePermissions.isAdmin(roles),
        hasRole(roles, props.getReviewerRole()), hasRole(roles, props.getApproverRole()));
  }

  // ---- helpers ----

  private TaskRailItem toItem(final TaskRailDataService.Seen seen, final Map<String, String> names,
      final LocalDate today) {
    final EcwsTaskOutbox row = seen.row();
    final TaskRailItem item = new TaskRailItem();
    item.setTaskId(row.getId());
    item.setPackageId(row.getPackageId());
    item.setEcwsTaskId(row.getEcwsTaskId()); // ADDED 2026-10-18
    item.setPackageCode(row.getPackageCode());
    item.setEvent(row.getEventCode());
    item.setEventLabel(EcwsTaskPublisher.APPROVAL.equals(row.getEventCode()) ? "Approval"
        : "Review");
    item.setSummary(row.getSummary());
    item.setAssigneeLoginId(row.getAssigneeLogin());
    item.setAssignee(nameOf(row.getAssigneeLogin(), names));
    item.setRelation(seen.relation());
    if (seen.closedAt() != null || !row.isTaskOpen()) {
      final LocalDateTime at = seen.closedAt();
      item.setClosedAt(at == null ? null : at.format(STAMP) + " UTC");
      item.setOutcome(outcomeText(seen.outcome()));
    } else {
      item.setDueDate(row.getDueDate() == null ? null : row.getDueDate().toString());
      item.setDueState(dueState(row.getDueDate(), today));
    }
    return item;
  }

  static String dueState(final LocalDate due, final LocalDate today) {
    if (due == null) {
      return "NONE";
    }
    if (due.isBefore(today)) {
      return "OVERDUE";
    }
    return due.isEqual(today) ? "TODAY" : "LATER";
  }

  /** "Completed in ELCM by Alice" is shown as "Completed by Alice"; blank becomes "Closed". */
  static String outcomeText(final String comment) {
    if (comment == null || comment.isBlank()) {
      return "Closed";
    }
    return comment.replace(" in ELCM", "").trim();
  }

  static int compareOpen(final TaskRailItem a, final TaskRailItem b) {
    final int byDue = Integer.compare(rank(a), rank(b));
    if (byDue != 0) {
      return byDue;
    }
    if (a.getDueDate() != null && b.getDueDate() != null) {
      final int byDate = a.getDueDate().compareTo(b.getDueDate());
      if (byDate != 0) {
        return byDate;
      }
    }
    return Long.compare(b.getTaskId(), a.getTaskId());
  }

  private static int rank(final TaskRailItem item) {
    return switch (item.getDueState()) {
      case "OVERDUE" -> 0;
      case "TODAY" -> 1;
      case "LATER" -> 2;
      default -> 3;
    };
  }

  private String nameOf(final String login, final Map<String, String> cache) {
    if (login == null || login.isBlank()) {
      return "Unassigned";
    }
    return cache.computeIfAbsent(login, key -> directory.findByAssigneeId(key)
        .map(UserDirectoryDTO::getDisplayName)
        .filter(name -> name != null && !name.isBlank())
        .orElse(key));
  }

  private static boolean hasRole(final List<String> roles, final String role) {
    if (roles == null || role == null || role.isBlank()) {
      return false;
    }
    for (final String r : roles) {
      if (r != null
          && r.trim().toLowerCase(Locale.ROOT).equals(role.trim().toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }
}
