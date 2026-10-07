package com.dodaso.ecosystem.elcm.security;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.elcm.service.pipeline.AssigneeDirectoryLookupService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Looks up the IAMS role names of a login id. Reuses the cached IAMS directory that
 * {@link AssigneeDirectoryLookupService} already maintains, so no extra IAMS call per request.
 */
@Service
@RequiredArgsConstructor
public class CallerRoleService {

  private final AssigneeDirectoryLookupService directoryLookupService;

  public List<String> rolesOf(String loginId) {
    if (loginId == null || loginId.isBlank()) {
      return List.of();
    }
    return directoryLookupService
        .findByAssigneeId(loginId)
        .map(UserDirectoryDTO::getRoleNames)
        .orElse(List.of());
  }
}
