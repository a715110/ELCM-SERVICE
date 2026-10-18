package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailAccess;
import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailCount;
import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailPage;
import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailService;
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
 * The Task Rail read API (ADDED 2026-10-18): the Review and Approval tasks the caller may see, for
 * the right-hand panel. Read only, needs pipeline:view. Who is asking comes from the verified token
 * or the user header, never from a parameter, and the access rules are applied here on the server.
 * The Closed list is only read when the panel asks for it.
 */
@RestController
@RequestMapping("/api/v1/pipeline/task-rail")
@RequiredArgsConstructor
public class TaskRailController {

  private static final String SYSTEM_USER = "system";

  private final TaskRailService taskRailService;
  private final CallerIdentityResolver callerIdentityResolver;
  private final CallerRoleService callerRoleService;

  /** Open tasks. mine=true keeps only the ones that are the caller's own. */
  @GetMapping("/open")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public TaskRailPage open(@RequestParam(defaultValue = "false") final boolean mine,
      final HttpServletRequest request) {
    return taskRailService.open(caller(request), mine);
  }

  /** One page of closed tasks of the last 30 days; before is the cursor of the previous page. */
  @GetMapping("/closed")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public TaskRailPage closed(@RequestParam(defaultValue = "false") final boolean mine,
      @RequestParam(required = false) final Long before, final HttpServletRequest request) {
    return taskRailService.closed(caller(request), mine, before);
  }

  /** The badge number: open tasks assigned to the caller. */
  @GetMapping("/count")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public TaskRailCount count(final HttpServletRequest request) {
    return taskRailService.count(callerIdentityResolver.loginIdOf(request));
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
