package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.AnswerRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.CaseDetail;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.CaseRow;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.CloseRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.ConclusionRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.DecisionRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.OpenCaseRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.Option;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.ReplacementRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.SendSurveyRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADDED 2026-11-11 -- REST face of lease reassessment. All rules live in ReassessmentService. The
 * survey answer endpoint has no permission annotation on purpose: the service lets only the named
 * respondent answer, whatever role that person holds.
 */
@RestController
@RequestMapping("/api/v1/reassessment")
@RequiredArgsConstructor
public class ReassessmentController {

  private final ReassessmentService service;
  private final CallerIdentityResolver caller;

  @GetMapping("cases")
  @RequiresPermission(ElcmPermission.REASSESSMENT_VIEW)
  public List<CaseRow> list(@RequestParam(required = false) final String scope,
      @RequestParam(required = false) final Long recordId, final HttpServletRequest request) {
    return service.list(caller.loginIdOf(request), scope, recordId);
  }

  @GetMapping("cases/{id}")
  @RequiresPermission(ElcmPermission.REASSESSMENT_VIEW)
  public CaseDetail detail(@PathVariable final Long id, final HttpServletRequest request) {
    return service.detail(caller.loginIdOf(request), id);
  }

  @GetMapping("trigger-types")
  @RequiresPermission(ElcmPermission.REASSESSMENT_VIEW)
  public List<Option> triggerTypes() {
    return service.triggerTypeOptions();
  }

  @GetMapping("outcomes")
  @RequiresPermission(ElcmPermission.REASSESSMENT_VIEW)
  public List<Option> outcomes() {
    return service.outcomeOptions();
  }

  @GetMapping("survey-templates")
  @RequiresPermission(ElcmPermission.REASSESSMENT_VIEW)
  public List<Option> surveyTemplates() {
    return service.templateOptions();
  }

  @PostMapping("cases")
  @RequiresPermission(ElcmPermission.REASSESSMENT_MANAGE)
  public CaseDetail open(@RequestBody final OpenCaseRequest body, final HttpServletRequest request) {
    return service.open(caller.loginIdOf(request), body);
  }

  @PostMapping("cases/{id}/survey")
  @RequiresPermission(ElcmPermission.REASSESSMENT_MANAGE)
  public CaseDetail sendSurvey(@PathVariable final Long id, @RequestBody final SendSurveyRequest body,
      final HttpServletRequest request) {
    return service.sendSurvey(caller.loginIdOf(request), id, body);
  }

  @PostMapping("cases/{id}/survey/answers")
  public CaseDetail answer(@PathVariable final Long id, @RequestBody final AnswerRequest body,
      final HttpServletRequest request) {
    return service.answer(caller.loginIdOf(request), id, body);
  }

  @PutMapping("cases/{id}/conclusion")
  @RequiresPermission(ElcmPermission.REASSESSMENT_ASSESS)
  public CaseDetail conclude(@PathVariable final Long id, @RequestBody final ConclusionRequest body,
      final HttpServletRequest request) {
    return service.conclude(caller.loginIdOf(request), id, body);
  }

  @PostMapping("cases/{id}/conclusion/decision")
  @RequiresPermission(ElcmPermission.REASSESSMENT_APPROVE)
  public CaseDetail decide(@PathVariable final Long id, @RequestBody final DecisionRequest body,
      final HttpServletRequest request) {
    return service.decide(caller.loginIdOf(request), id, body);
  }

  @PostMapping("cases/{id}/replacement")
  @RequiresPermission(ElcmPermission.REASSESSMENT_MANAGE)
  public CaseDetail linkReplacement(@PathVariable final Long id,
      @RequestBody final ReplacementRequest body, final HttpServletRequest request) {
    return service.linkReplacement(caller.loginIdOf(request), id, body);
  }

  @PostMapping("cases/{id}/close")
  @RequiresPermission(ElcmPermission.REASSESSMENT_MANAGE)
  public CaseDetail close(@PathVariable final Long id, @RequestBody final CloseRequest body,
      final HttpServletRequest request) {
    return service.close(caller.loginIdOf(request), id, body);
  }
}
