package com.dodaso.ecosystem.elcm.service.reassessment;

import com.dodaso.ecosystem.elcm.entity.reassessment.ReassessmentSurveyQuestion;
import com.dodaso.ecosystem.elcm.entity.reassessment.ReassessmentSurveyTemplate;
import com.dodaso.ecosystem.elcm.repository.reassessment.ReassessmentSurveyQuestionRepository;
import com.dodaso.ecosystem.elcm.repository.reassessment.ReassessmentSurveyResponseRepository;
import com.dodaso.ecosystem.elcm.repository.reassessment.ReassessmentSurveyTemplateRepository;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderDtos.CopyRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderDtos.QuestionRow;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderDtos.SaveQuestionRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderDtos.SaveTemplateRequest;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderDtos.TemplateDetail;
import com.dodaso.ecosystem.elcm.service.reassessment.SurveyBuilderDtos.TemplateRow;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADDED 2026-11-13. Lets an admin create and edit the surveys that can be sent from a
 * reassessment, without SQL. Works on the existing template and question tables.
 *
 * <p>A new or copied survey starts unpublished and cannot be chosen when sending until it is
 * published (is_active). Editing is always safe for surveys already sent, because sending copies
 * the questions (the snapshot). A survey that was sent cannot be deleted, only unpublished.
 * Failures: 404 unknown, 409 not allowed now, 422 bad input.
 */
@Service
@RequiredArgsConstructor
public class SurveyBuilderService {

  private static final int STEP = 10;

  private final ReassessmentSurveyTemplateRepository templates;
  private final ReassessmentSurveyQuestionRepository questions;
  private final ReassessmentSurveyResponseRepository sent;

  // ---------------------------------------------------------------- reading

  @Transactional(readOnly = true)
  public List<TemplateRow> list() {
    final List<TemplateRow> out = new ArrayList<>();
    for (final ReassessmentSurveyTemplate t : templates.findAllByOrderBySortOrderAscNameAsc()) {
      out.add(row(t));
    }
    return out;
  }

  @Transactional(readOnly = true)
  public TemplateDetail detail(final Long id) {
    return detailOf(load(id));
  }

  // ---------------------------------------------------------------- surveys

  @Transactional
  public TemplateDetail create(final SaveTemplateRequest req) {
    final String name = req == null ? null : req.getName();
    final String description = req == null ? null : req.getDescription();
    failIf(SurveyBuilderRules.checkTemplate(name, description));
    final ReassessmentSurveyTemplate t = newTemplate(name, description);
    return detailOf(templates.save(t));
  }

  @Transactional
  public TemplateDetail update(final Long id, final SaveTemplateRequest req) {
    final ReassessmentSurveyTemplate t = load(id);
    failIf(SurveyBuilderRules.checkTemplate(req == null ? null : req.getName(),
        req == null ? null : req.getDescription()));
    t.setName(req.getName().trim());
    t.setDescription(blankToNull(req.getDescription()));
    if (req.getPublished() != null) {
      if (req.getPublished() && questions.countByTemplate_IdAndIsActiveTrue(id) == 0) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Add at least one question before publishing.");
      }
      t.setIsActive(req.getPublished());
    }
    return detailOf(templates.save(t));
  }

  /** Copies a survey and its questions under a new name. The copy is unpublished. */
  @Transactional
  public TemplateDetail copy(final Long id, final CopyRequest req) {
    final ReassessmentSurveyTemplate source = load(id);
    final String name = req == null || req.getName() == null || req.getName().isBlank()
        ? "Copy of " + source.getName() : req.getName();
    failIf(SurveyBuilderRules.checkTemplate(name, source.getDescription()));
    final ReassessmentSurveyTemplate copy =
        templates.save(newTemplate(name, source.getDescription()));
    for (final ReassessmentSurveyQuestion q : questions.findByTemplate_IdOrderBySortOrderAscIdAsc(id)) {
      final ReassessmentSurveyQuestion c = new ReassessmentSurveyQuestion();
      c.setTemplate(copy);
      c.setSortOrder(q.getSortOrder());
      c.setQuestionText(q.getQuestionText());
      c.setAnswerType(q.getAnswerType());
      c.setChoices(q.getChoices());
      c.setIsRequired(q.getIsRequired());
      c.setIsActive(q.getIsActive());
      questions.save(c);
    }
    return detailOf(copy);
  }

  /** Deletes a survey that was never sent. Returns the list that remains. */
  @Transactional
  public List<TemplateRow> delete(final Long id) {
    final ReassessmentSurveyTemplate t = load(id);
    if (sent.countByTemplate_Id(id) > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This survey has been sent, so it cannot be deleted. Unpublish it instead.");
    }
    questions.deleteAll(questions.findByTemplate_IdOrderBySortOrderAscIdAsc(id));
    templates.delete(t);
    return list();
  }

  // -------------------------------------------------------------- questions

  @Transactional
  public TemplateDetail addQuestion(final Long templateId, final SaveQuestionRequest req) {
    final ReassessmentSurveyTemplate t = load(templateId);
    check(req);
    final List<ReassessmentSurveyQuestion> all =
        questions.findByTemplate_IdOrderBySortOrderAscIdAsc(templateId);
    if (all.size() >= SurveyBuilderRules.MAX_QUESTIONS) {
      throw invalid("A survey can have at most " + SurveyBuilderRules.MAX_QUESTIONS + " questions.");
    }
    final ReassessmentSurveyQuestion q = new ReassessmentSurveyQuestion();
    q.setTemplate(t);
    q.setSortOrder(all.isEmpty() ? STEP : all.get(all.size() - 1).getSortOrder() + STEP);
    apply(q, req);
    questions.save(q);
    return detailOf(t);
  }

  @Transactional
  public TemplateDetail updateQuestion(final Long questionId, final SaveQuestionRequest req) {
    final ReassessmentSurveyQuestion q = loadQuestion(questionId);
    check(req);
    apply(q, req);
    questions.save(q);
    return detailOf(q.getTemplate());
  }

  @Transactional
  public TemplateDetail moveQuestion(final Long questionId, final String direction) {
    final ReassessmentSurveyQuestion q = loadQuestion(questionId);
    final ReassessmentSurveyTemplate t = q.getTemplate();
    final List<ReassessmentSurveyQuestion> all =
        new ArrayList<>(questions.findByTemplate_IdOrderBySortOrderAscIdAsc(t.getId()));
    final int from = indexOf(all, questionId);
    final int to = SurveyBuilderRules.movedIndex(from, all.size(), "UP".equalsIgnoreCase(direction));
    java.util.Collections.swap(all, from, to);
    int order = STEP;
    for (final ReassessmentSurveyQuestion item : all) {
      item.setSortOrder(order);
      order += STEP;
    }
    questions.saveAll(all);
    return detailOf(t);
  }

  @Transactional
  public TemplateDetail deleteQuestion(final Long questionId) {
    final ReassessmentSurveyQuestion q = loadQuestion(questionId);
    final ReassessmentSurveyTemplate t = q.getTemplate();
    questions.delete(q);
    questions.flush();
    if (Boolean.TRUE.equals(t.getIsActive()) && questions.countByTemplate_IdAndIsActiveTrue(t.getId()) == 0) {
      t.setIsActive(Boolean.FALSE);
      templates.save(t);
    }
    return detailOf(t);
  }

  // ---------------------------------------------------------------- helpers

  private ReassessmentSurveyTemplate newTemplate(final String name, final String description) {
    final ReassessmentSurveyTemplate t = new ReassessmentSurveyTemplate();
    t.setCode(SurveyBuilderRules.codeFrom(name, c -> templates.findByCode(c).isPresent()));
    t.setName(name.trim());
    t.setDescription(blankToNull(description));
    t.setSortOrder(templates.findAllByOrderBySortOrderAscNameAsc().stream()
        .mapToInt(x -> x.getSortOrder() == null ? 0 : x.getSortOrder()).max().orElse(0) + STEP);
    t.setIsActive(Boolean.FALSE);
    return t;
  }

  private static void check(final SaveQuestionRequest req) {
    if (req == null) {
      throw invalid("Write the question.");
    }
    failIf(SurveyBuilderRules.checkQuestion(req.getText(), req.getType(), req.getChoices()));
  }

  private static void apply(final ReassessmentSurveyQuestion q, final SaveQuestionRequest req) {
    q.setQuestionText(req.getText().trim());
    q.setAnswerType(req.getType());
    q.setChoices(SurveyBuilderRules.storedChoices(req.getType(), req.getChoices()));
    q.setIsRequired(req.isRequired());
    q.setIsActive(req.isActive());
  }

  private TemplateRow row(final ReassessmentSurveyTemplate t) {
    final TemplateRow r = new TemplateRow();
    r.setId(t.getId());
    r.setCode(t.getCode());
    r.setName(t.getName());
    r.setDescription(t.getDescription());
    r.setPublished(!Boolean.FALSE.equals(t.getIsActive()));
    r.setQuestionCount(questions.countByTemplate_IdAndIsActiveTrue(t.getId()));
    r.setSentCount(sent.countByTemplate_Id(t.getId()));
    return r;
  }

  private TemplateDetail detailOf(final ReassessmentSurveyTemplate t) {
    final TemplateDetail d = new TemplateDetail();
    d.setSummary(row(t));
    for (final ReassessmentSurveyQuestion q : questions.findByTemplate_IdOrderBySortOrderAscIdAsc(t.getId())) {
      final QuestionRow r = new QuestionRow();
      r.setId(q.getId());
      r.setSortOrder(q.getSortOrder() == null ? 0 : q.getSortOrder());
      r.setText(q.getQuestionText());
      r.setType(q.getAnswerType());
      r.setTypeLabel(SurveyBuilderRules.types().getOrDefault(q.getAnswerType(), q.getAnswerType()));
      r.setChoices(q.getChoices());
      r.setRequired(!Boolean.FALSE.equals(q.getIsRequired()));
      r.setActive(!Boolean.FALSE.equals(q.getIsActive()));
      d.getQuestions().add(r);
    }
    return d;
  }

  private ReassessmentSurveyTemplate load(final Long id) {
    return templates.findById(id).orElseThrow(
        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The survey was not found."));
  }

  private ReassessmentSurveyQuestion loadQuestion(final Long id) {
    return questions.findById(id).orElseThrow(
        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The question was not found."));
  }

  private static int indexOf(final List<ReassessmentSurveyQuestion> all, final Long id) {
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).getId().equals(id)) {
        return i;
      }
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The question was not found.");
  }

  private static void failIf(final String message) {
    if (message != null) {
      throw invalid(message);
    }
  }

  private static ResponseStatusException invalid(final String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static String blankToNull(final String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
