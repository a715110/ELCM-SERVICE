package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.pipeline.MyCommittedDocument;
import com.dodaso.ecosystem.elcm.service.pipeline.MySubmissionItem;
import com.dodaso.ecosystem.elcm.service.pipeline.MySubmissionsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The submitter's own lists (ADDED 2026-10-14), always for the caller, never for someone else's
 * login. Read only, pipeline:view.
 *
 * <p>GET /api/v1/pipeline/my/submissions the submissions the caller made, newest first. GET
 * /api/v1/pipeline/my/committed-documents the documents of the caller's applied submissions.
 */
@RestController
@RequestMapping("/api/v1/pipeline/my")
@RequiredArgsConstructor
public class MySubmissionsController {

  private final MySubmissionsService mySubmissionsService;
  private final CallerIdentityResolver callerIdentityResolver;

  @GetMapping("/submissions")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public List<MySubmissionItem> submissions(final HttpServletRequest request) {
    return mySubmissionsService.findSubmissions(callerIdentityResolver.loginIdOf(request));
  }

  @GetMapping("/committed-documents")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public List<MyCommittedDocument> committed(final HttpServletRequest request) {
    return mySubmissionsService.findCommitted(callerIdentityResolver.loginIdOf(request));
  }
}
