package com.dodaso.ecosystem.elcm.integration.packagerail;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailAccess;
import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailService;
import com.dodaso.ecosystem.elcm.service.pipeline.AssigneeDirectoryLookupService;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * ADDED 2026-10-25 -- the Package Rail: the contract packages the signed-in person may see, as an
 * Open list and a Closed list, 20 at a time, newest change first.
 *
 * <p>Access (the same pattern as the Task Rail): "Mine" is a package the person is assigned to,
 * created, submitted or reviewed. "All" is every package and is for administrators, reviewers and
 * approvers only; for anybody else the server answers "Mine" whatever was asked.
 */
@Service
@RequiredArgsConstructor
public class PackageRailService {

  /** Lines per page. */
  public static final int PAGE_SIZE = 20;

  private final PackageRailDataService dataService;
  private final AssigneeDirectoryLookupService directory;

  public PackageRailPage open(final TaskRailAccess.Caller caller, final boolean mine,
      final Integer offset) {
    return page(caller, mine, offset, false);
  }

  public PackageRailPage closed(final TaskRailAccess.Caller caller, final boolean mine,
      final Integer offset) {
    return page(caller, mine, offset, true);
  }

  private PackageRailPage page(final TaskRailAccess.Caller caller, final boolean mine,
      final Integer offset, final boolean closed) {
    final boolean canSeeAll = TaskRailService.canSeeAll(caller);
    final boolean all = canSeeAll && !mine;
    final int from = offset == null ? 0 : Math.max(0, offset);
    final PackageRailDataService.Slice slice =
        dataService.read(closed, all, caller.login(), from, PAGE_SIZE);
    final Map<String, String> names = new HashMap<>();
    final PackageRailPage page = new PackageRailPage();
    for (final PackageRailDataService.Seen seen : slice.items()) {
      page.getItems().add(toItem(seen, caller.login(), names));
    }
    page.setTotal((int) Math.min(Integer.MAX_VALUE, slice.total()));
    final int end = from + slice.items().size();
    page.setNextOffset(end < slice.total() ? end : null);
    page.setCanSeeAll(canSeeAll);
    return page;
  }

  private PackageRailItem toItem(final PackageRailDataService.Seen seen, final String login,
      final Map<String, String> names) {
    final PackageRailRepository.Line line = seen.line();
    final PackageRailItem item = new PackageRailItem();
    item.setPackageId(line.getId());
    item.setPackageCode(line.getPackageCode());
    item.setStatusCode(line.getStatusCode());
    item.setStatusLabel(line.getStatusLabel());
    item.setWorkspace(line.getWorkspace());
    item.setAssigneeLoginId(line.getAssigneeId());
    item.setAssignee(nameOf(line.getAssigneeId(), names));
    item.setDocCount(seen.docCount());
    item.setRelation(relation(seen, login));
    item.setUpdatedAt(line.getUpdatedAt() == null ? null : line.getUpdatedAt().toString());
    return item;
  }

  /** How the viewer is involved, strongest first. */
  static String relation(final PackageRailDataService.Seen seen, final String login) {
    if (same(seen.line().getAssigneeId(), login)) {
      return "ASSIGNED";
    }
    if (same(seen.line().getCreatedBy(), login)) {
      return "CREATED";
    }
    if (same(seen.submittedBy(), login)) {
      return "SUBMITTER";
    }
    if (same(seen.reviewedBy(), login)) {
      return "REVIEWER";
    }
    return "OTHER";
  }

  private static boolean same(final String a, final String b) {
    return a != null && b != null
        && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
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
}
