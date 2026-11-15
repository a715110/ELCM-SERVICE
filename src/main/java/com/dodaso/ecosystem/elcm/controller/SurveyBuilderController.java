package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderDtos.CopyRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderDtos.MoveRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderDtos.SaveQuestionRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderDtos.SaveTemplateRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderDtos.TemplateDetail;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderDtos.TemplateRow;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADDED 2026-11-13. Survey builder for admins: create, copy, edit, publish and delete the surveys
 * that can be sent from a reassessment. Deletes are POSTs so every call works through the shared
 * REST client.
 */
@RestController
@RequestMapping("/api/v1/reassessment/survey-builder")
@RequiredArgsConstructor
public class SurveyBuilderController {

  private final SurveyBuilderService service;

  @GetMapping("templates")
  @RequiresPermission(ElcmPermission.SURVEY_ADMIN)
  public List<TemplateRow> list() {
    return service.list();
  }

  @GetMapping("templates/{id}")
  @RequiresPermission(ElcmPermission.SURVEY_ADMIN)
  public TemplateDetail detail(@PathVariable final Long id) {
    return service.detail(id);
  }

  @PostMapping("templates")
  @RequiresPermission(ElcmPermission.SURVEY_ADMIN)
  public TemplateDetail create(@RequestBody final SaveTemplateRequest body) {
    return service.create(body);
  }

  @PutMapping("templates/{id}")
  @RequiresPermission(ElcmPermission.SURVEY_ADMIN)
  public TemplateDetail update(@PathVariable final Long id, @RequestBody final SaveTemplateRequest body) {
    return service.update(id, body);
  }

  @PostMapping("templates/{id}/copy")
  @RequiresPermission(ElcmPermission.SURVEY_ADMIN)
  public TemplateDetail copy(@PathVariable final Long id, @RequestBody final CopyRequest body) {
    return service.copy(id, body);
  }

  @PostMapping("templates/{id}/delete")
  @RequiresPermission(ElcmPermission.SURVEY_ADMIN)
  public List<TemplateRow> delete(@PathVariable final Long id) {
    return service.delete(id);
  }

  @PostMapping("templates/{id}/questions")
  @RequiresPermission(ElcmPermission.SURVEY_ADMIN)
  public TemplateDetail addQuestion(@PathVariable final Long id,
      @RequestBody final SaveQuestionRequest body) {
    return service.addQuestion(id, body);
  }

  @PutMapping("questions/{id}")
  @RequiresPermission(ElcmPermission.SURVEY_ADMIN)
  public TemplateDetail updateQuestion(@PathVariable final Long id,
      @RequestBody final SaveQuestionRequest body) {
    return service.updateQuestion(id, body);
  }

  @PostMapping("questions/{id}/move")
  @RequiresPermission(ElcmPermission.SURVEY_ADMIN)
  public TemplateDetail move(@PathVariable final Long id, @RequestBody final MoveRequest body) {
    return service.moveQuestion(id, body == null ? null : body.getDirection());
  }

  @PostMapping("questions/{id}/delete")
  @RequiresPermission(ElcmPermission.SURVEY_ADMIN)
  public TemplateDetail deleteQuestion(@PathVariable final Long id) {
    return service.deleteQuestion(id);
  }
}
