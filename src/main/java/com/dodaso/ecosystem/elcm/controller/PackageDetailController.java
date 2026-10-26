package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.activity.ActivityPage;
import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailAccess;
import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailService;
import com.dodaso.ecosystem.elcm.packagedetail.PackageDetail;
import com.dodaso.ecosystem.elcm.packagedetail.PackageDetailService;
import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.CallerRoleService;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.security.VerifiedCaller;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Package Details read API (ADDED 2026-10-25): one package with its documents and
 * submissions, and its activity log. Read only, needs pipeline:view. Who is asking comes from the
 * verified token or the user header, never from a parameter; the access rule is applied here on
 * the server (see PackageDetailService): 404 when there is no such package, 403 when the caller
 * may not see it.
 */
@RestController
@RequestMapping("/api/v1/pipeline/package-detail")
@RequiredArgsConstructor
public class PackageDetailController {

  private static final String SYSTEM_USER = "system";

  private final PackageDetailService packageDetailService;
  private final TaskRailService taskRailService;
  private final CallerIdentityResolver callerIdentityResolver;
  private final CallerRoleService callerRoleService;

  @GetMapping("/{id}")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public PackageDetail detail(@PathVariable final Long id, final HttpServletRequest request) {
    return packageDetailService.detail(caller(request), id);
  }

  /** The activity log of the package, newest first, a page at a time (pages start at 0). */
  @GetMapping("/{id}/activity")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public ActivityPage activity(@PathVariable final Long id,
      @RequestParam(required = false) final Integer page,
      @RequestParam(required = false) final Integer size, final HttpServletRequest request) {
    return packageDetailService.activity(caller(request), id, page, size);
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
