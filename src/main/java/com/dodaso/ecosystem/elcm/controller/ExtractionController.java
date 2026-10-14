package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.extraction.ApplyRequest;
import com.dodaso.ecosystem.elcm.service.extraction.ApplyResult;
import com.dodaso.ecosystem.elcm.service.extraction.TargetCandidate;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionCoverage;
import com.dodaso.ecosystem.elcm.service.extraction.ReviewAccess;
import com.dodaso.ecosystem.elcm.service.extraction.ReviewAccessService;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionResults;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionService;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionStatus;
import com.dodaso.ecosystem.elcm.service.extraction.FieldHistory;
import com.dodaso.ecosystem.elcm.service.extraction.DeclineRequest;
import com.dodaso.ecosystem.elcm.service.extraction.SaveFieldRequest;
import com.dodaso.ecosystem.elcm.service.extraction.SendBackEntry;
import com.dodaso.ecosystem.elcm.service.extraction.SendBackRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Extraction progress, results and review of a submitted package (ADDED 2026-10-11, review
 * endpoints 2026-10-12). The extraction itself runs in the background (ExtractionWorker); the
 * screen polls /extraction for progress. Reads need pipeline:view, Retry needs pipeline:submit, and
 * the review writes (save a field, confirm, complete, decline) need pipeline:review, which the
 * submitting roles do not have.
 */
@RestController
@RequestMapping("/api/v1/pipeline/contract-packages/{id}/extraction")
@RequiredArgsConstructor
public class ExtractionController {

  private final ExtractionService extractionService;
  private final CallerIdentityResolver callerIdentityResolver;
  private final ReviewAccessService reviewAccessService;

  /**
   * Progress: state, queue position, per-document status and elapsed time.
   */
  @GetMapping
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public ExtractionStatus status(@PathVariable final Long id) {
    return extractionService.status(id);
  }

  /**
   * The extracted values with their evidence, per document.
   */
  @GetMapping("/results")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public ExtractionResults results(@PathVariable final Long id) {
    return extractionService.results(id);
  }

  /**
   * History of one field: every correction and confirmation, oldest first. 404 when the field is
   * not in this package.
   */
  @GetMapping("/fields/{resultId}/history")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public FieldHistory history(@PathVariable final Long id, @PathVariable final Long resultId) {
    return extractionService.history(id, resultId);
  }

  /**
   * Documents that cannot be read because their contract type has no extraction template.
   */
  @GetMapping("/coverage")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public ExtractionCoverage coverage(@PathVariable final Long id) {
    return extractionService.coverage(id);
  }

  /**
   * What the caller may do in the review of this package: has the permission, may edit (not the
   * submitter).
   */
  @GetMapping("/review-access")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public ReviewAccess reviewAccess(@PathVariable final Long id,
      final HttpServletRequest httpRequest) {
    return reviewAccessService.access(id, httpRequest);
  }

  /**
   * Puts a FAILED extraction back in the queue: 204; 404 no submission, 409 not failed.
   */
  @PostMapping("/retry")
  @RequiresPermission(ElcmPermission.PIPELINE_SUBMIT)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void retry(@PathVariable final Long id) {
    extractionService.retry(id);
  }

  /**
   * Reviewer: correct and/or confirm one field. 204; 400 bad value, 404 unknown field, 409 not
   * reviewable.
   */
  @PostMapping("/fields/{resultId}")
  @RequiresPermission(ElcmPermission.PIPELINE_REVIEW)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void saveField(@PathVariable final Long id, @PathVariable final Long resultId,
      @RequestBody final SaveFieldRequest request, final HttpServletRequest httpRequest) {
    extractionService.saveField(id, resultId, request.correctedValue(),
        Boolean.TRUE.equals(request.verified()), callerIdentityResolver.loginIdOf(httpRequest));
  }

  /**
   * Reviewer: confirm every field the extractor verified. Answers {"confirmed": n}.
   */
  @PostMapping("/confirm-verified")
  @RequiresPermission(ElcmPermission.PIPELINE_REVIEW)
  public Map<String, Integer> confirmVerified(@PathVariable final Long id,
      final HttpServletRequest httpRequest) {
    return Map.of("confirmed", extractionService.confirmVerified(id,
        callerIdentityResolver.loginIdOf(httpRequest)));
  }

  /**
   * Reviewer: complete the review. 204; 403 the caller submitted the package, 409 not reviewable,
   * 422 critical or flagged fields unconfirmed.
   */
  @PostMapping("/complete")
  @RequiresPermission(ElcmPermission.PIPELINE_REVIEW)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void complete(@PathVariable final Long id, final HttpServletRequest httpRequest) {
    extractionService.completeReview(id, callerIdentityResolver.loginIdOf(httpRequest));
  }

  /**
   * Approver: send a completed review back to the reviewer, with a reason (the review becomes
   * editable again). Can happen any number of times; each reason is kept. 204; 400 reason missing
   * or over 1000 characters; 404 no submission; 409 not in the Reviewed state.
   */
  @PostMapping("/send-back")
  @RequiresPermission(ElcmPermission.PIPELINE_APPROVE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void sendBack(@PathVariable final Long id, @RequestBody final SendBackRequest request,
      final HttpServletRequest httpRequest) {
    extractionService.sendBack(id, request == null ? null : request.reason(),
        callerIdentityResolver.loginIdOf(httpRequest));
  }

  /** Why the review was sent back, newest first; empty when it never was. 404 no submission. */
  @GetMapping("/send-backs")
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public List<SendBackEntry> sendBacks(@PathVariable final Long id) {
    return extractionService.sendBacks(id);
  }

  /**
   * Reviewer: reopen a completed review. 204; 404 no submission, 409 not in the Reviewed state.
   */
  @PostMapping("/reopen")
  @RequiresPermission(ElcmPermission.PIPELINE_REVIEW)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reopen(@PathVariable final Long id, final HttpServletRequest httpRequest) {
    extractionService.reopenReview(id, callerIdentityResolver.loginIdOf(httpRequest));
  }

  /**
   * Approver: approve the completed review by writing the reviewed values to the package's target
   * contract record. 200 with the counts; 404 no submission, 409 not reviewed or already applied,
   * 422 no target record (and none chosen in the body), or the chosen record cannot be used.
   */
  @PostMapping("/apply")
  @RequiresPermission(ElcmPermission.PIPELINE_APPROVE)
  public ApplyResult apply(@PathVariable final Long id,
      @RequestBody(required = false) final ApplyRequest request,
      final HttpServletRequest httpRequest) {
    return extractionService.apply(id, request == null ? null : request.targetRecordId(),
        callerIdentityResolver.loginIdOf(httpRequest));
  }

  /**
   * Records the reviewer can choose as the target when the package has none: same workspace, active
   * or pending.
   */
  @GetMapping("/target-candidates")
  @RequiresPermission(ElcmPermission.PIPELINE_APPROVE)
  public List<TargetCandidate> targetCandidates(@PathVariable final Long id) {
    return extractionService.targetCandidates(id);
  }

  /**
   * Reviewer: decline the package back to Assembly with a reason. 204; 400 no reason, 404 no
   * submission, 409 not declinable (still running, already reviewed or declined).
   */
  @PostMapping("/decline")
  @RequiresPermission(ElcmPermission.PIPELINE_REVIEW)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void decline(@PathVariable final Long id, @RequestBody final DeclineRequest request,
      final HttpServletRequest httpRequest) {
    extractionService.decline(id, request.reason(), callerIdentityResolver.loginIdOf(httpRequest));
  }
}