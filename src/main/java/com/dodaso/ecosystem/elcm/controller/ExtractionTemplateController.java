package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.template.TemplateAdminService;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.CreateDraftRequest;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.DeleteImpact;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.DeleteRequest;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Detail;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Diff;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Event;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.NewTemplateRequest;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Options;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.ReasonRequest;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Result;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.SaveRequest;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Summary;
import com.dodaso.ecosystem.elcm.service.template.TemplateTrialService;
import com.dodaso.ecosystem.elcm.service.template.TrialModels.Trial;
import com.dodaso.ecosystem.elcm.service.template.TrialModels.TrialRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration of extraction templates (ADDED 2026-10-14): list the versions, edit a draft,
 * publish it, compare two versions and read the change log. Every endpoint needs template:manage
 * (System Admin and Super Admin).
 */
@RestController
@RequestMapping("/api/v1/pipeline/extraction-templates")
@RequiredArgsConstructor
public class ExtractionTemplateController {

  private final TemplateAdminService templateAdminService;
  private final TemplateTrialService templateTrialService;
  private final CallerIdentityResolver callerIdentityResolver;

  /** Every version of every template. */
  @GetMapping
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public List<Summary> list() {
    return templateAdminService.list();
  }

  /** The data types and contract types to choose from. */
  @GetMapping("/options")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public Options options() {
    return templateAdminService.options();
  }

  /** One version with its fields. 404 when unknown. */
  @GetMapping("/{id}")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public Detail get(@PathVariable final Long id) {
    return templateAdminService.get(id);
  }

  /** The change log of the template this version belongs to, newest first. */
  @GetMapping("/{id}/events")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public List<Event> events(@PathVariable final Long id) {
    return templateAdminService.events(id);
  }

  /** What differs between two versions. */
  @GetMapping("/{id}/compare/{otherId}")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public Diff compare(@PathVariable final Long id, @PathVariable final Long otherId) {
    return templateAdminService.compare(id, otherId);
  }

  /** A new template (an empty draft). Messages in the result when the input is wrong. */
  @PostMapping
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public Result create(
      @RequestBody final NewTemplateRequest request, final HttpServletRequest httpRequest) {
    return templateAdminService.createTemplate(
        request.name(),
        request.contractTypeId(),
        request.reason(),
        callerIdentityResolver.loginIdOf(httpRequest));
  }

  /** A draft copied from this version. 409 when the template already has an open draft. */
  @PostMapping("/{id}/draft")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public Detail createDraft(
      @PathVariable final Long id,
      @RequestBody(required = false) final CreateDraftRequest request,
      final HttpServletRequest httpRequest) {
    return templateAdminService.createDraft(
        id,
        request == null ? null : request.reason(),
        callerIdentityResolver.loginIdOf(httpRequest));
  }

  /** Replaces the fields of a draft. 409 when the version is not a draft. */
  @PutMapping("/{id}")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public Result save(
      @PathVariable final Long id,
      @RequestBody final SaveRequest request,
      final HttpServletRequest httpRequest) {
    return templateAdminService.saveDraft(
        id, request.fields(), callerIdentityResolver.loginIdOf(httpRequest));
  }

  /** Publishes a draft; it becomes the version extraction uses. */
  @PostMapping("/{id}/publish")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public Result publish(
      @PathVariable final Long id,
      @RequestBody final ReasonRequest request,
      final HttpServletRequest httpRequest) {
    return templateAdminService.publish(
        id, request.reason(), callerIdentityResolver.loginIdOf(httpRequest));
  }

  /** Deletes a draft: 204; 409 when the version is not a draft. */
  @PostMapping("/{id}/discard")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void discard(
      @PathVariable final Long id,
      @RequestBody(required = false) final ReasonRequest request,
      final HttpServletRequest httpRequest) {
    templateAdminService.discard(
        id,
        request == null ? null : request.reason(),
        callerIdentityResolver.loginIdOf(httpRequest));
  }

  /**
   * ADDED 2026-10-15 - starts a trial run: reads one document with the fields of this version and
   * saves nothing. Answers at once with the trial's id; ask {@link #trial} for the result. A
   * request that cannot be accepted comes back with state REFUSED and a message.
   */
  @PostMapping("/{id}/trial")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public Trial startTrial(
      @PathVariable final Long id,
      @RequestBody final TrialRequest request,
      final HttpServletRequest httpRequest) {
    return templateTrialService.start(
        id, request, callerIdentityResolver.loginIdOf(httpRequest));
  }

  /** ADDED 2026-10-15 - a trial run as it is now. 404 when unknown or older than an hour. */
  @GetMapping("/trials/{trialId}")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public Trial trial(@PathVariable final String trialId) {
    return templateTrialService.status(trialId);
  }

  /** ADDED 2026-10-15 - what deleting the template this version belongs to would remove. */
  @GetMapping("/{id}/delete-impact")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public DeleteImpact deleteImpact(@PathVariable final Long id) {
    return templateAdminService.deleteImpact(id);
  }

  /**
   * ADDED 2026-10-15 - deletes the template this version belongs to, every version, for good.
   * Messages in the result (and nothing deleted) when the confirmation is wrong or documents were
   * read with it.
   */
  @PostMapping("/{id}/delete")
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public Result deleteTemplate(
      @PathVariable final Long id,
      @RequestBody final DeleteRequest request,
      final HttpServletRequest httpRequest) {
    return templateAdminService.deleteTemplate(
        id, request.confirmName(), request.reason(), callerIdentityResolver.loginIdOf(httpRequest));
  }
}
