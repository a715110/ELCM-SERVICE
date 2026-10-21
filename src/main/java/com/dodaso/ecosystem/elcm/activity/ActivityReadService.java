package com.dodaso.ecosystem.elcm.activity;

import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.ears.container.EventAlertReminderDTOContainer;
import com.dodaso.ecosystem.ears.dto.EventAlertReminderDTO;
import com.dodaso.ecosystem.elcm.service.pipeline.AssigneeDirectoryLookupService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADDED 2026-10-20 -- reads a package's Activity Log back from EARS. Not transactional: it only
 * calls other services. Display names come from IAMS once per distinct person per page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityReadService {

  static final int MAX_SIZE = 200;
  private static final String ENDPOINT =
      "/eventReminderController/event-alert-reminder/activity";

  private final RESTServiceClient restServiceClient;
  private final AssigneeDirectoryLookupService assigneeDirectoryLookupService;

  /** One page, newest first. 502 when EARS cannot be reached or answers with an error. */
  public ActivityPage find(final Long packageId, final Integer page, final Integer size) {
    final int pageNumber = page == null || page < 0 ? 0 : page;
    final int pageSize = size == null || size < 1 ? 50 : Math.min(size, MAX_SIZE);
    final int id;
    try {
      id = Math.toIntExact(packageId);
    } catch (final ArithmeticException e) {
      return new ActivityPage(List.of(), pageNumber, pageSize, 0, 0);
    }
    final EventAlertReminderDTOContainer container;
    try {
      container = restServiceClient.get(ServiceDiscoveryEnum.ears_service.getServiceDiscoveryName(),
          ENDPOINT + "?sourceReferenceTable=" + ActivityRecorder.TABLE + "&sourceReferenceId=" + id
              + "&page=" + pageNumber + "&size=" + pageSize,
          new ParameterizedTypeReference<EventAlertReminderDTOContainer>() {
          });
    } catch (final Exception e) {
      log.warn("ACTIVITY-LOG could not read the activity of package {}: {}", packageId,
          e.getMessage());
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "The activity log is not available right now");
    }
    if (container == null || !container.isSuccess()
        || container.getEventAlertReminderDTOList() == null) {
      return new ActivityPage(List.of(), pageNumber, pageSize, 0, 0);
    }
    final Map<String, String> names = new HashMap<>();
    final List<ActivityEntry> entries = new ArrayList<>();
    for (final EventAlertReminderDTO row : container.getEventAlertReminderDTOList()) {
      final String actor = row.getActor();
      entries.add(new ActivityEntry(
          row.getId() == null ? null : row.getId().longValue(),
          row.getOccurredAt() != null ? row.getOccurredAt().toString()
              : (row.getCreatedAt() != null ? row.getCreatedAt().toString() : null),
          actor,
          nameOf(actor, names),
          row.getSubject(),
          row.getBody()));
    }
    return new ActivityPage(entries, container.getCurrentPage(), container.getPageSize(),
        container.getTotalCount(), container.getTotalPages());
  }

  private String nameOf(final String loginId, final Map<String, String> cache) {
    if (loginId == null || loginId.isBlank()) {
      return "";
    }
    return cache.computeIfAbsent(loginId, key -> {
      if ("system".equals(key)) {
        return "System";
      }
      try {
        return assigneeDirectoryLookupService.findByAssigneeId(key)
            .map(entry -> entry.getDisplayName())
            .filter(name -> name != null && !name.isBlank())
            .orElse(key);
      } catch (final RuntimeException e) {
        return key;
      }
    });
  }
}
