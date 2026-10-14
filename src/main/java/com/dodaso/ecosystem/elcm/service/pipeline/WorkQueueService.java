package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The work queue for reviewers and approvers: the database read from {@link WorkQueueDataService},
 * then the display names of the assignee, the submitter and the reviewer from IAMS, outside any
 * transaction. A name that cannot be resolved is shown as the stored login id.
 */
@Service
@RequiredArgsConstructor
public class WorkQueueService {

  private static final String UNASSIGNED = "Unassigned";

  private final WorkQueueDataService dataService;
  private final AssigneeDirectoryLookupService assigneeDirectoryLookupService;

  public List<WorkQueueItem> findQueue() {
    final List<WorkQueueItem> items = dataService.load();
    final Map<String, String> names = new HashMap<>();
    for (final WorkQueueItem item : items) {
      item.setAssignee(nameOf(item.getAssigneeLoginId(), names, UNASSIGNED));
      item.setSubmittedBy(nameOf(item.getSubmittedByLoginId(), names, ""));
      item.setReviewedBy(nameOf(item.getReviewedBy(), names, ""));
    }
    return items;
  }

  private String nameOf(
      final String loginId, final Map<String, String> cache, final String whenBlank) {
    if (loginId == null || loginId.isBlank()) {
      return whenBlank;
    }
    return cache.computeIfAbsent(
        loginId,
        key -> {
          final Optional<UserDirectoryDTO> entry =
              assigneeDirectoryLookupService.findByAssigneeId(key);
          if (entry.isPresent()
              && entry.get().getDisplayName() != null
              && !entry.get().getDisplayName().isBlank()) {
            return entry.get().getDisplayName();
          }
          return key;
        });
  }
}
