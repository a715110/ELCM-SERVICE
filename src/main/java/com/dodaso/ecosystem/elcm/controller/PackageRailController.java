package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailAccess;
import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailService;
import com.dodaso.ecosystem.elcm.integration.packagerail.PackageRailPage;
import com.dodaso.ecosystem.elcm.integration.packagerail.PackageRailService;
import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.CallerRoleService;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.security.VerifiedCaller;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Package Rail read API (ADDED 2026-10-25): the contract packages the caller may see, for the
 * right-edge panel of the ECWS screens. Read only, needs pipeline:view. Who is asking comes from
 * the verified token or the user header, never from a parameter, and the access rules are applied
 * here on the server (see PackageRailService).
 */
@RestController
@RequestMapping("/api/v1/pipeline/package-rail")
@RequiredArgsConstructor
public class PackageRailController {

  private static final String SYSTEM_USER = "system";

  private final PackageRailService packageRailService;
  private final TaskRailService taskRailService;
  private final CallerIdentityResolver callerIdentityResolver;
  private final CallerRoleService callerRoleService;

  /** Open packages (every status except completed), 20 at a time from offset. */
  @GetMapping("/open")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public PackageRailPage open(@RequestParam(defaultValue = "false") final boolean mine,
      @RequestParam(required = false) final Integer offset, final HttpServletRequest request) {
    return packageRailService.open(caller(request), mine, offset);
  }

  /** Completed packages, 20 at a time from offset. */
  @GetMapping("/closed")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public PackageRailPage closed(@RequestParam(defaultValue = "false") final boolean mine,
      @RequestParam(required = false) final Integer offset, final HttpServletRequest request) {
    return packageRailService.closed(caller(request), mine, offset);
  }

  private TaskRailAccess.Caller caller(final HttpServletRequest request) {
    final String login = callerIdentityResolver.loginIdOf(request);
    final List<String> roles;
    final Object verified = request.getAttribute(VerifiedCaller.ATTRIBUTE);
    if (verified instanceof VerifiedCaller vc) {
      roles = vc.roles();
    } else {
      roles = SYSTEM_USER.equalsIgnoreCase(login) ? List.of() : callerRoleService.rolesOf(login);
    }
    return taskRailService.callerOf(login, roles);
  }
}
