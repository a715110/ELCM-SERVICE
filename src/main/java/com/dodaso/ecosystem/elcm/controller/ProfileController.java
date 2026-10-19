package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.CallerRoleService;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.ElcmRolePermissions;
import com.dodaso.ecosystem.elcm.security.VerifiedCaller;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADDED 2026-10-18. Backs "What I can do" on the Profile page: the plain-language list of what the
 * caller's roles allow in ELCM. It describes only the caller (taken from the verified token or the
 * user header, never from a parameter) and changes nothing, so it needs no permission of its own:
 * a user with no ELCM role simply gets an empty list.
 */
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
public class ProfileController {

  private static final String SYSTEM_USER = "system";

  private final CallerIdentityResolver callerIdentityResolver;
  private final CallerRoleService callerRoleService;

  /** One sentence for each thing the caller's roles allow, in a fixed order. */
  @GetMapping("/abilities")
  public List<String> abilities(final HttpServletRequest request) {
    final String login = callerIdentityResolver.loginIdOf(request);
    final List<String> roles;
    final Object verified = request.getAttribute(VerifiedCaller.ATTRIBUTE);
    if (verified instanceof VerifiedCaller vc) {
      roles = vc.roles();
    } else {
      roles = SYSTEM_USER.equalsIgnoreCase(login) ? List.of() : callerRoleService.rolesOf(login);
    }
    final Set<ElcmPermission> granted = ElcmRolePermissions.grantedTo(roles);
    final List<String> lines = new ArrayList<>();
    for (final ElcmPermission permission : granted) {
      lines.add(sentence(permission));
    }
    return lines;
  }

  static String sentence(final ElcmPermission permission) {
    return switch (permission) {
      case PIPELINE_VIEW -> "See the document pipeline, packages and your tasks";
      case PIPELINE_SUBMIT -> "Upload documents and submit packages for review";
      case PIPELINE_DELETE -> "Delete documents you uploaded";
      case PIPELINE_REVIEW -> "Review extracted values and complete a review";
      case PIPELINE_APPROVE -> "Approve a completed review and apply it to the contract record";
      case PIPELINE_CLASSIFY ->
          "Set the role of a document in a package, such as lease or amendment";
      case TEMPLATE_MANAGE -> "Create, publish and compare extraction templates";
    };
  }
}
