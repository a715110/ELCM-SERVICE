package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.extraction.ControlRequest;
import com.dodaso.ecosystem.elcm.service.extraction.ControlState;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionControlService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pause and Resume of the extraction agent (ADDED 2026-10-18). Administrators only: the same
 * permission as the Extraction Quality page (template:manage). Stopping one package is on
 * ExtractionController.
 */
@RestController
@RequestMapping("/api/v1/extraction/control")
@RequiredArgsConstructor
public class ExtractionControlController {

  private final ExtractionControlService controlService;
  private final CallerIdentityResolver callerIdentityResolver;

  /** Paused or not, who and why, queue counts and the latest control events. */
  @GetMapping
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public ControlState state() {
    return controlService.state();
  }

  /** Pauses the agent. 400 without a reason. Already paused: nothing changes. */
  @PostMapping("/pause")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public ControlState pause(@RequestBody final ControlRequest request,
      final HttpServletRequest httpRequest) {
    return controlService.pause(callerIdentityResolver.loginIdOf(httpRequest),
        request == null ? null : request.reason());
  }

  /** Resumes the agent. Not paused: nothing changes. */
  @PostMapping("/resume")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public ControlState resume(final HttpServletRequest httpRequest) {
    return controlService.resume(callerIdentityResolver.loginIdOf(httpRequest));
  }
}
