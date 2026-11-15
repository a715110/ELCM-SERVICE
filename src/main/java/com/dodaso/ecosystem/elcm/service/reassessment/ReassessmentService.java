package com.dodaso.ecosystem.elcm.service.reassessment;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpApprovalDecision;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpContractRecordRelationshipType;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpContractRecordStatus;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpReassessmentCaseStatus;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpReassessmentOutcome;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpReassessmentTriggerType;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecord;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecordRelationship;
import com.dodaso.ecosystem.elcm.entity.reassessment.ReassessmentCase;
import com.dodaso.ecosystem.elcm.entity.reassessment.ReassessmentCaseEvent;
import com.dodaso.ecosystem.elcm.entity.reassessment.ReassessmentConclusion;
import com.dodaso.ecosystem.elcm.entity.reassessment.ReassessmentSurveyQuestion;
import com.dodaso.ecosystem.elcm.entity.reassessment.ReassessmentSurveyResponse;
import com.dodaso.ecosystem.elcm.entity.reassessment.ReassessmentSurveyTemplate;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpApprovalDecisionRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpContractRecordRelationshipTypeRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpContractRecordStatusRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpReassessmentCaseStatusRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpReassessmentOutcomeRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpReassessmentTriggerTypeRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractRecordRelationshipRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractRecordRepository;
import com.dodaso.ecosystem.elcm.repository.reassessment.ReassessmentCaseEventRepository;
import com.dodaso.ecosystem.elcm.repository.reassessment.ReassessmentCaseRepository;
import com.dodaso.ecosystem.elcm.repository.reassessment.ReassessmentConclusionRepository;
import com.dodaso.ecosystem.elcm.repository.reassessment.ReassessmentSurveyQuestionRepository;
import com.dodaso.ecosystem.elcm.repository.reassessment.ReassessmentSurveyResponseRepository;
import com.dodaso.ecosystem.elcm.repository.reassessment.ReassessmentSurveyTemplateRepository;
import com.dodaso.ecosystem.elcm.security.CallerRoleService;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.ElcmRolePermissions;
import com.dodaso.ecosystem.elcm.service.pipeline.AssigneeDirectoryLookupService;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentAnswerRules.SurveyQuestion;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.AnswerRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.CaseDetail;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.CaseRow;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.CloseRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.ConclusionRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.DecisionRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.EventRow;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.OpenCaseRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.Option;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.ReplacementRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.SendSurveyRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.ReassessmentDtos.SurveyLine;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADDED 2026-11-11 -- lease reassessment: open a case on a contract record, send a survey to a
 * business respondent, record the assessment, approve it, and (when an amendment is needed) link
 * the replacement record. First version, built to be reworked after review: the flow is in
 * {@link ReassessmentStates}, answer types in {@link ReassessmentAnswerRules}, questions and
 * lookups are table rows, delivery goes through {@link ReassessmentNotifier}.
 *
 * <p>Data is reached through repositories only. Failures answer with ResponseStatusException:
 * 404 unknown, 409 wrong state, 412 role or two-person rule, 422 bad input, 403 not the
 * respondent, 503 when the feature is switched off (elcm.reassessment.enabled).
 */
@Service
@RequiredArgsConstructor
public class ReassessmentService {

  static final String OUTCOME_NO_ACTION = "NO_ACTION_REQUIRED";
  static final String OUTCOME_AMENDMENT = "AMENDMENT_REQUIRED";
  static final String ACTIVE = "ACTIVE";
  static final String SUPERSEDED = "SUPERSEDED";
  static final String APPROVED = "APPROVED";
  static final String REJECTED = "REJECTED";

  private final ReassessmentCaseRepository cases;
  private final ReassessmentSurveyResponseRepository surveys;
  private final ReassessmentConclusionRepository conclusions;
  private final ReassessmentCaseEventRepository events;
  private final ReassessmentSurveyTemplateRepository templates;
  private final ReassessmentSurveyQuestionRepository questions;
  private final LkpReassessmentCaseStatusRepository statuses;
  private final LkpReassessmentTriggerTypeRepository triggerTypes;
  private final LkpReassessmentOutcomeRepository outcomes;
  private final LkpApprovalDecisionRepository decisions;
  private final LkpContractRecordStatusRepository recordStatuses;
  private final LkpContractRecordRelationshipTypeRepository relationshipTypes;
  private final ContractRecordRepository records;
  private final ContractRecordRelationshipRepository relationships;
  private final AssigneeDirectoryLookupService directory;
  private final CallerRoleService callerRoles;
  private final ReassessmentNotifier notifier;

  @Value("${elcm.reassessment.enabled:false}")
  private boolean enabled;

  @Value("${elcm.reassessment.require-different-approver:true}")
  private boolean differentApprover;

  @Value("${elcm.reassessment.replacement-relationship-code:AMENDMENT}")
  private String replacementRelationshipCode;

  // ---------------------------------------------------------------- lookups

  @Transactional(readOnly = true)
  public List<Option> triggerTypeOptions() {
    requireEnabled();
    final List<Option> out = new ArrayList<>();
    triggerTypes.findAll().stream()
        .filter(t -> !Boolean.FALSE.equals(t.getIsActive()))
        .sorted(Comparator.comparing(t -> orderOf(t.getSortOrder())))
        .forEach(t -> out.add(new Option(t.getCode(), t.getLabel(), t.getDescription())));
    return out;
  }

  @Transactional(readOnly = true)
  public List<Option> outcomeOptions() {
    requireEnabled();
    final List<Option> out = new ArrayList<>();
    outcomes.findAll().stream()
        .filter(t -> !Boolean.FALSE.equals(t.getIsActive()))
        .sorted(Comparator.comparing(t -> orderOf(t.getSortOrder())))
        .forEach(t -> out.add(new Option(t.getCode(), t.getLabel(), t.getDescription())));
    return out;
  }

  @Transactional(readOnly = true)
  public List<Option> templateOptions() {
    requireEnabled();
    final List<Option> out = new ArrayList<>();
    for (final ReassessmentSurveyTemplate t
        : templates.findByIsActiveTrueOrderBySortOrderAscNameAsc()) {
      out.add(new Option(t.getCode(), t.getName(), t.getDescription()));
    }
    return out;
  }

  // ---------------------------------------------------------------- reading

  /** Cases for the list page. Scope "all" lists every case, anything else only the caller's. */
  @Transactional(readOnly = true)
  public List<CaseRow> list(final String caller, final String scope, final Long recordId) {
    requireEnabled();
    final List<ReassessmentCase> found = recordId != null
        ? cases.findByContractRecord_IdOrderByIdDesc(recordId) : cases.findAllByOrderByIdDesc();
    final boolean all = "all".equalsIgnoreCase(scope) || recordId != null;
    final List<CaseRow> out = new ArrayList<>();
    for (final ReassessmentCase c : found) {
      final Optional<ReassessmentSurveyResponse> survey =
          surveys.findFirstByReassessmentCase_IdOrderByIdDesc(c.getId());
      if (all || same(c.getOpenedBy(), caller)
          || survey.map(s -> same(s.getRespondent(), caller)).orElse(false)) {
        out.add(row(c, survey.orElse(null)));
      }
    }
    return out;
  }

  @Transactional(readOnly = true)
  public CaseDetail detail(final String caller, final Long id) {
    requireEnabled();
    final ReassessmentCase c = load(id);
    final ReassessmentSurveyResponse survey =
        surveys.findFirstByReassessmentCase_IdOrderByIdDesc(id).orElse(null);
    final ReassessmentConclusion conclusion = conclusions.findByReassessmentCase_Id(id).orElse(null);
    final CaseDetail d = new CaseDetail();
    d.setSummary(row(c, survey));
    d.setTriggerDescription(c.getTriggerEventDescription());
    d.setTriggerDate(c.getTriggerEventDate());
    if (survey != null && survey.getSentAt() != null) {
      d.setSurveySent(true);
      d.setSurveyTemplate(survey.getTemplate() == null ? null : survey.getTemplate().getName());
      d.setBusinessUnit(survey.getBusinessUnit());
      d.setSurveySentBy(survey.getSentBy());
      d.setSurveySentAt(survey.getSentAt());
      d.setSurveyDueDate(survey.getDueDate());
      d.setSurveySubmittedAt(survey.getSubmittedAt());
      final Map<String, String> answers = ReassessmentAnswerRules.readAnswers(survey.getResponseData());
      for (final SurveyQuestion q : ReassessmentAnswerRules.readQuestions(survey.getQuestionsSnapshot())) {
        final SurveyLine line = new SurveyLine();
        line.setNumber(q.getNumber());
        line.setText(q.getText());
        line.setType(q.getType());
        line.setChoices(q.getChoices());
        line.setRequired(q.isRequired());
        line.setAnswer(answers.get(String.valueOf(q.getNumber())));
        d.getSurvey().add(line);
      }
    }
    if (conclusion != null) {
      if (conclusion.getOutcome() != null) {
        d.setOutcomeCode(conclusion.getOutcome().getCode());
        d.setOutcomeLabel(conclusion.getOutcome().getLabel());
      }
      d.setConclusionSummary(conclusion.getConclusionSummary());
      d.setConclusionSubmitted(Boolean.TRUE.equals(conclusion.getIsSubmitted()));
      d.setConcludedBy(conclusion.getConcludedBy());
      d.setConcludedAt(conclusion.getConcludedAt());
      if (conclusion.getApprovalDecision() != null) {
        d.setDecisionCode(conclusion.getApprovalDecision().getCode());
      }
      d.setApprovalComment(conclusion.getApprovalComment());
      d.setApprovedBy(conclusion.getApprovedBy());
      d.setApprovedAt(conclusion.getApprovedAt());
      if (conclusion.getResultingRelationship() != null) {
        final ContractRecord successor = conclusion.getResultingRelationship().getSuccessorRecord();
        d.setReplacementRecordId(successor.getId());
        d.setReplacementRecordCode(successor.getRecordCode());
      }
    }
    for (final ReassessmentCaseEvent e : events.findByReassessmentCase_IdOrderByOccurredAtDescIdDesc(id)) {
      final EventRow r = new EventRow();
      r.setEventCode(e.getEventCode());
      r.setMessage(e.getMessage());
      r.setActor(e.getActor());
      r.setOccurredAt(e.getOccurredAt());
      d.getEvents().add(r);
    }
    flags(d, c, survey, caller);
    return d;
  }

  // ---------------------------------------------------------------- actions

  @Transactional
  public CaseDetail open(final String caller, final OpenCaseRequest req) {
    requireEnabled();
    if (req == null || req.getRecordId() == null) {
      throw invalid("Choose the contract record to reassess.");
    }
    final ContractRecord record = records.findById(req.getRecordId())
        .orElseThrow(() -> notFound("The contract record was not found."));
    if (record.getStatus() == null || !ACTIVE.equals(record.getStatus().getCode())) {
      throw conflict("Only an active contract record can be reassessed.");
    }
    final LkpReassessmentTriggerType trigger = triggerTypes.findByCode(blankToNull(req.getTriggerCode()))
        .orElseThrow(() -> invalid("Choose why the record is being reassessed."));
    for (final ReassessmentCase other : cases.findByContractRecord_IdOrderByIdDesc(record.getId())) {
      if (!ReassessmentStates.isFinal(other.getStatus().getCode())) {
        throw conflict("Reassessment " + other.getCaseCode() + " is still open on this record.");
      }
    }
    final ReassessmentCase c = new ReassessmentCase();
    c.setCaseCode(nextCaseCode(record));
    c.setContractRecord(record);
    c.setTriggerType(trigger);
    c.setTriggerEventDescription(blankToNull(req.getDescription()));
    c.setTriggerEventDate(req.getEventDate());
    c.setDueDate(req.getDueDate());
    c.setStatus(status(ReassessmentStates.OPEN));
    c.setOpenedBy(caller);
    c.setOpenedAt(LocalDateTime.now());
    cases.save(c);
    log(c, "OPENED", "Reassessment opened: " + trigger.getLabel() + ".", caller);
    return detail(caller, c.getId());
  }

  @Transactional
  public CaseDetail sendSurvey(final String caller, final Long id, final SendSurveyRequest req) {
    requireEnabled();
    final ReassessmentCase c = load(id);
    final String from = c.getStatus().getCode();
    if (!ReassessmentStates.OPEN.equals(from) && !ReassessmentStates.SURVEY_SENT.equals(from)) {
      throw conflict("A survey can be sent only before the assessment starts.");
    }
    if (req == null || blankToNull(req.getTemplateCode()) == null) {
      throw invalid("Choose a survey.");
    }
    final ReassessmentSurveyTemplate template = templates.findByCode(req.getTemplateCode())
        .filter(t -> !Boolean.FALSE.equals(t.getIsActive()))
        .orElseThrow(() -> invalid("That survey is not available."));
    final UserDirectoryDTO person = directory.findByAssigneeId(req.getRespondent())
        .orElseThrow(() -> invalid("The respondent is not a known user."));
    final List<SurveyQuestion> snapshot = new ArrayList<>();
    int number = 1;
    for (final ReassessmentSurveyQuestion q
        : questions.findByTemplate_IdAndIsActiveTrueOrderBySortOrderAscIdAsc(template.getId())) {
      snapshot.add(new SurveyQuestion(number++, q.getQuestionText(), q.getAnswerType(),
          ReassessmentAnswerRules.splitChoices(q.getChoices()),
          !Boolean.FALSE.equals(q.getIsRequired())));
    }
    if (snapshot.isEmpty()) {
      throw invalid("That survey has no questions.");
    }
    final ReassessmentSurveyResponse s = surveys.findFirstByReassessmentCase_IdOrderByIdDesc(id)
        .orElseGet(ReassessmentSurveyResponse::new);
    s.setReassessmentCase(c);
    s.setTemplate(template);
    s.setQuestionsSnapshot(ReassessmentAnswerRules.writeQuestions(snapshot));
    s.setRespondent(person.getLoginId());
    s.setBusinessUnit(blankToNull(req.getBusinessUnit()));
    s.setSentBy(caller);
    s.setSentAt(LocalDateTime.now());
    s.setDueDate(req.getDueDate());
    s.setResponseData(null);
    s.setSubmittedAt(null);
    surveys.save(s);
    c.setStatus(status(ReassessmentStates.SURVEY_SENT));
    cases.save(c);
    log(c, "SURVEY_SENT", "Survey \"" + template.getName() + "\" sent to " + name(person) + ".", caller);
    notifier.surveySent(c.getCaseCode(), person.getLoginId());
    return detail(caller, id);
  }

  /** The respondent saves a draft or submits the answers. Only the named respondent may. */
  @Transactional
  public CaseDetail answer(final String caller, final Long id, final AnswerRequest req) {
    requireEnabled();
    final ReassessmentCase c = load(id);
    final ReassessmentSurveyResponse s = surveys.findFirstByReassessmentCase_IdOrderByIdDesc(id)
        .orElseThrow(() -> conflict("No survey has been sent for this reassessment."));
    if (!same(s.getRespondent(), caller)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This survey is addressed to someone else.");
    }
    if (!ReassessmentStates.SURVEY_SENT.equals(c.getStatus().getCode()) || s.getSubmittedAt() != null) {
      throw conflict("This survey is no longer open for answers.");
    }
    final boolean submit = req != null && req.isSubmit();
    final Map<String, String> clean = ReassessmentAnswerRules.check(
        ReassessmentAnswerRules.readQuestions(s.getQuestionsSnapshot()),
        req == null ? null : req.getAnswers(), submit);
    s.setResponseData(ReassessmentAnswerRules.writeAnswers(clean));
    if (submit) {
      s.setSubmittedAt(LocalDateTime.now());
      c.setStatus(status(ReassessmentStates.SURVEY_RECEIVED));
      cases.save(c);
      log(c, "SURVEY_SUBMITTED", "Survey answers submitted.", caller);
    }
    surveys.save(s);
    return detail(caller, id);
  }

  /** Writes the assessor's conclusion; with submit true it goes to the approver. */
  @Transactional
  public CaseDetail conclude(final String caller, final Long id, final ConclusionRequest req) {
    requireEnabled();
    final ReassessmentCase c = load(id);
    final String from = c.getStatus().getCode();
    if (!ReassessmentStates.conclusionEditable(from)) {
      throw conflict("The conclusion cannot be changed in the current status.");
    }
    final boolean submit = req != null && req.isSubmit();
    final LkpReassessmentOutcome outcome = req == null || blankToNull(req.getOutcomeCode()) == null
        ? null
        : outcomes.findByCode(req.getOutcomeCode()).orElseThrow(() -> invalid("Unknown outcome."));
    final String summary = req == null ? null : blankToNull(req.getSummary());
    if (submit && (outcome == null || summary == null)) {
      throw invalid("Choose an outcome and write a summary before submitting.");
    }
    final ReassessmentConclusion con = conclusions.findByReassessmentCase_Id(id)
        .orElseGet(ReassessmentConclusion::new);
    con.setReassessmentCase(c);
    con.setOutcome(outcome);
    con.setConclusionSummary(summary);
    con.setConcludedBy(caller);
    con.setConcludedAt(LocalDateTime.now());
    con.setApprovalDecision(null);
    con.setApprovedBy(null);
    con.setApprovedAt(null);
    con.setIsSubmitted(submit);
    conclusions.save(con);
    if (!ReassessmentStates.IN_ASSESSMENT.equals(from)) {
      move(c, ReassessmentStates.IN_ASSESSMENT);
    }
    if (submit) {
      move(c, ReassessmentStates.AWAITING_APPROVAL);
    }
    if (submit) {
      log(c, "SUBMITTED_FOR_APPROVAL", "Conclusion submitted for approval.", caller);
      notifier.awaitingApproval(c.getCaseCode());
    } else {
      log(c, "CONCLUSION_SAVED", "Conclusion saved.", caller);
    }
    return detail(caller, id);
  }

  @Transactional
  public CaseDetail decide(final String caller, final Long id, final DecisionRequest req) {
    requireEnabled();
    final ReassessmentCase c = load(id);
    if (!ReassessmentStates.AWAITING_APPROVAL.equals(c.getStatus().getCode())) {
      throw conflict("There is no conclusion waiting for approval.");
    }
    final String decision = req == null || req.getDecision() == null
        ? "" : req.getDecision().trim().toUpperCase(Locale.ROOT);
    if (!APPROVED.equals(decision) && !REJECTED.equals(decision)) {
      throw invalid("Choose Approve or Reject.");
    }
    final String comment = req == null ? null : blankToNull(req.getComment());
    if (REJECTED.equals(decision) && comment == null) {
      throw invalid("Say why the conclusion is rejected.");
    }
    final ReassessmentConclusion con = conclusions.findByReassessmentCase_Id(id)
        .orElseThrow(() -> conflict("The conclusion is missing."));
    if (differentApprover && same(con.getConcludedBy(), caller)) {
      throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
          "The approver cannot be the person who wrote the conclusion.");
    }
    final LkpApprovalDecision d = decisions.findByCode(decision)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
            "Missing approval decision " + decision + ". Run reassessment_slice.sql."));
    con.setApprovalDecision(d);
    con.setApprovalComment(comment);
    con.setApprovedBy(caller);
    con.setApprovedAt(LocalDateTime.now());
    if (APPROVED.equals(decision)) {
      final boolean amend = con.getOutcome() != null && OUTCOME_AMENDMENT.equals(con.getOutcome().getCode());
      move(c, amend ? ReassessmentStates.CONCLUDED : ReassessmentStates.CLOSED);
      log(c, "APPROVED", "Conclusion approved." + (comment == null ? "" : " " + comment), caller);
    } else {
      con.setIsSubmitted(false);
      move(c, ReassessmentStates.IN_ASSESSMENT);
      log(c, "REJECTED", "Conclusion sent back: " + comment, caller);
    }
    conclusions.save(con);
    return detail(caller, id);
  }

  /** Links the record that replaces the reassessed one and closes the case. */
  @Transactional
  public CaseDetail linkReplacement(final String caller, final Long id, final ReplacementRequest req) {
    requireEnabled();
    final ReassessmentCase c = load(id);
    final ReassessmentConclusion con = conclusions.findByReassessmentCase_Id(id).orElse(null);
    if (!ReassessmentStates.CONCLUDED.equals(c.getStatus().getCode()) || con == null
        || con.getOutcome() == null || !OUTCOME_AMENDMENT.equals(con.getOutcome().getCode())) {
      throw conflict("A replacement can be linked only after an approved amendment conclusion.");
    }
    if (req == null || req.getReplacementRecordId() == null) {
      throw invalid("Choose the replacement record.");
    }
    final ContractRecord predecessor = c.getContractRecord();
    final ContractRecord successor = records.findById(req.getReplacementRecordId())
        .orElseThrow(() -> notFound("The replacement record was not found."));
    if (successor.getId().equals(predecessor.getId())) {
      throw invalid("The replacement must be a different record.");
    }
    final LkpContractRecordRelationshipType type = relationshipTypes.findByCode(replacementRelationshipCode)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
            "Missing relationship type " + replacementRelationshipCode + ". Run reassessment_slice.sql."));
    for (final ContractRecordRelationship r : relationships.findByPredecessorRecord_Id(predecessor.getId())) {
      if (r.getRelationshipType().getId().equals(type.getId())) {
        throw conflict("This record already has a " + type.getLabel() + " link.");
      }
    }
    final LkpContractRecordStatus superseded = recordStatuses.findByCode(SUPERSEDED)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
            "Missing record status SUPERSEDED. Run reassessment_slice.sql."));
    final ContractRecordRelationship link = new ContractRecordRelationship();
    link.setPredecessorRecord(predecessor);
    link.setSuccessorRecord(successor);
    link.setRelationshipType(type);
    link.setEffectiveDate(req.getEffectiveDate());
    link.setNotes(blankToNull(req.getNotes()));
    relationships.save(link);
    predecessor.setStatus(superseded);
    records.save(predecessor);
    con.setResultingRelationship(link);
    conclusions.save(con);
    move(c, ReassessmentStates.CLOSED);
    log(c, "REPLACEMENT_LINKED", "Record " + successor.getRecordCode() + " replaces "
        + predecessor.getRecordCode() + ".", caller);
    return detail(caller, id);
  }

  /** Ends a case without a result (opened by mistake, no longer needed). */
  @Transactional
  public CaseDetail close(final String caller, final Long id, final CloseRequest req) {
    requireEnabled();
    final ReassessmentCase c = load(id);
    if (ReassessmentStates.isFinal(c.getStatus().getCode())) {
      throw conflict("The reassessment is already closed.");
    }
    final String comment = req == null ? null : blankToNull(req.getComment());
    if (comment == null) {
      throw invalid("Say why the reassessment is closed.");
    }
    move(c, ReassessmentStates.CLOSED);
    log(c, "CLOSED", "Closed without a result: " + comment, caller);
    return detail(caller, id);
  }

  // ---------------------------------------------------------------- helpers

  private void flags(final CaseDetail d, final ReassessmentCase c,
      final ReassessmentSurveyResponse survey, final String caller) {
    final Collection<String> roles = callerRoles.rolesOf(caller);
    final String st = c.getStatus().getCode();
    final boolean manage = ElcmRolePermissions.isGranted(roles, ElcmPermission.REASSESSMENT_MANAGE);
    final boolean assess = ElcmRolePermissions.isGranted(roles, ElcmPermission.REASSESSMENT_ASSESS);
    final boolean approve = ElcmRolePermissions.isGranted(roles, ElcmPermission.REASSESSMENT_APPROVE);
    d.setCanSendSurvey(manage && (ReassessmentStates.OPEN.equals(st) || ReassessmentStates.SURVEY_SENT.equals(st)));
    d.setCanAnswerSurvey(survey != null && survey.getSubmittedAt() == null
        && ReassessmentStates.SURVEY_SENT.equals(st) && same(survey.getRespondent(), caller));
    d.setCanConclude(assess && ReassessmentStates.conclusionEditable(st));
    d.setCanDecide(approve && ReassessmentStates.AWAITING_APPROVAL.equals(st)
        && !(differentApprover && same(d.getConcludedBy(), caller)));
    d.setCanLinkReplacement(manage && ReassessmentStates.CONCLUDED.equals(st));
    d.setCanClose(manage && !ReassessmentStates.isFinal(st));
  }

  private CaseRow row(final ReassessmentCase c, final ReassessmentSurveyResponse survey) {
    final CaseRow r = new CaseRow();
    r.setId(c.getId());
    r.setCaseCode(c.getCaseCode());
    r.setRecordId(c.getContractRecord().getId());
    r.setRecordCode(c.getContractRecord().getRecordCode());
    if (c.getContractRecord().getCounterparty() != null) {
      r.setCounterparty(c.getContractRecord().getCounterparty().getName());
    }
    r.setTriggerCode(c.getTriggerType().getCode());
    r.setTriggerLabel(c.getTriggerType().getLabel());
    r.setStatusCode(c.getStatus().getCode());
    r.setStatusLabel(c.getStatus().getLabel());
    r.setDueDate(c.getDueDate());
    r.setOpenedBy(c.getOpenedBy());
    r.setOpenedAt(c.getOpenedAt());
    if (survey != null) {
      r.setRespondent(survey.getRespondent());
    }
    conclusions.findByReassessmentCase_Id(c.getId()).ifPresent(con -> {
      if (con.getOutcome() != null) {
        r.setOutcomeCode(con.getOutcome().getCode());
      }
    });
    return r;
  }

  private void move(final ReassessmentCase c, final String to) {
    final String from = c.getStatus().getCode();
    if (!ReassessmentStates.canMove(from, to)) {
      throw conflict("A reassessment cannot move from " + from + " to " + to + ".");
    }
    c.setStatus(status(to));
    cases.save(c);
  }

  private LkpReassessmentCaseStatus status(final String code) {
    return statuses.findByCode(code).orElseThrow(() -> new ResponseStatusException(
        HttpStatus.INTERNAL_SERVER_ERROR, "Missing reassessment status " + code
            + ". Run reassessment_slice.sql."));
  }

  private void log(final ReassessmentCase c, final String code, final String message, final String actor) {
    final ReassessmentCaseEvent e = new ReassessmentCaseEvent();
    e.setReassessmentCase(c);
    e.setEventCode(code);
    e.setMessage(message);
    e.setActor(actor);
    e.setOccurredAt(LocalDateTime.now());
    events.save(e);
  }

  private String nextCaseCode(final ContractRecord record) {
    int n = (int) cases.countByContractRecord_Id(record.getId()) + 1;
    String code = record.getRecordCode() + "-RC" + String.format("%02d", n);
    while (cases.existsByCaseCode(code)) {
      n++;
      code = record.getRecordCode() + "-RC" + String.format("%02d", n);
    }
    return code;
  }

  private ReassessmentCase load(final Long id) {
    return cases.findById(id).orElseThrow(() -> notFound("The reassessment was not found."));
  }

  private void requireEnabled() {
    if (!enabled) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Lease reassessment is not switched on.");
    }
  }

  private static int orderOf(final Integer v) {
    return v == null ? 0 : v;
  }

  private static String name(final UserDirectoryDTO u) {
    return u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getLoginId() : u.getDisplayName();
  }

  private static String blankToNull(final String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static boolean same(final String a, final String b) {
    return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
  }

  private static ResponseStatusException notFound(final String m) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, m);
  }

  private static ResponseStatusException conflict(final String m) {
    return new ResponseStatusException(HttpStatus.CONFLICT, m);
  }

  private static ResponseStatusException invalid(final String m) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, m);
  }
}
