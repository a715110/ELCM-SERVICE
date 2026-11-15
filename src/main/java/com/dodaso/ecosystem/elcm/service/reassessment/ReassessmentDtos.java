package com.dodaso.ecosystem.elcm.service.reassessment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * Request and row classes of the reassessment service, kept together so a change to the screens'
 * data touches one file. Plain beans; the UI binds to the JSON field names.
 */
public final class ReassessmentDtos {

  private ReassessmentDtos() {
  }

  /** A lookup choice (trigger type, outcome, survey template). */
  @Getter
  @Setter
  public static class Option {
    private String code;
    private String label;
    private String description;

    public Option() {
    }

    public Option(String code, String label, String description) {
      this.code = code;
      this.label = label;
      this.description = description;
    }
  }

  /** One line of the case list. */
  @Getter
  @Setter
  public static class CaseRow {
    private Long id;
    private String caseCode;
    private Long recordId;
    private String recordCode;
    private String counterparty;
    private String triggerCode;
    private String triggerLabel;
    private String statusCode;
    private String statusLabel;
    private LocalDate dueDate;
    private String openedBy;
    private LocalDateTime openedAt;
    private String respondent;
    private String outcomeCode;
  }

  /** One question of a sent survey with its answer. */
  @Getter
  @Setter
  public static class SurveyLine {
    private int number;
    private String text;
    private String type;
    private List<String> choices = new ArrayList<>();
    private boolean required;
    private String answer;
  }

  /** One history entry. */
  @Getter
  @Setter
  public static class EventRow {
    private String eventCode;
    private String message;
    private String actor;
    private LocalDateTime occurredAt;
  }

  /** The case page: summary, survey, conclusion, history, and what the caller may do. */
  @Getter
  @Setter
  public static class CaseDetail {
    private CaseRow summary;
    private String triggerDescription;
    private LocalDate triggerDate;
    private boolean surveySent;
    private String surveyTemplate;
    private String businessUnit;
    private String surveySentBy;
    private LocalDateTime surveySentAt;
    private LocalDate surveyDueDate;
    private LocalDateTime surveySubmittedAt;
    private List<SurveyLine> survey = new ArrayList<>();
    private String outcomeCode;
    private String outcomeLabel;
    private String conclusionSummary;
    private boolean conclusionSubmitted;
    private String concludedBy;
    private LocalDateTime concludedAt;
    private String decisionCode;
    private String approvalComment;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private Long replacementRecordId;
    private String replacementRecordCode;
    private List<EventRow> events = new ArrayList<>();
    private boolean canSendSurvey;
    private boolean canAnswerSurvey;
    private boolean canConclude;
    private boolean canDecide;
    private boolean canLinkReplacement;
    private boolean canClose;
  }

  /** Open a case. */
  @Getter
  @Setter
  public static class OpenCaseRequest {
    private Long recordId;
    private String triggerCode;
    private String description;
    private LocalDate eventDate;
    private LocalDate dueDate;
  }

  /** Send (or resend) the survey. */
  @Getter
  @Setter
  public static class SendSurveyRequest {
    private String templateCode;
    private String respondent;
    private String businessUnit;
    private LocalDate dueDate;
  }

  /** Save or submit survey answers: question number to answer. */
  @Getter
  @Setter
  public static class AnswerRequest {
    private Map<String, String> answers = new LinkedHashMap<>();
    private boolean submit;
  }

  /** Save the conclusion, or submit it for approval. */
  @Getter
  @Setter
  public static class ConclusionRequest {
    private String outcomeCode;
    private String summary;
    private boolean submit;
  }

  /** Approve or reject a submitted conclusion. */
  @Getter
  @Setter
  public static class DecisionRequest {
    private String decision;
    private String comment;
  }

  /** Link the record that replaces the reassessed one. */
  @Getter
  @Setter
  public static class ReplacementRequest {
    private Long replacementRecordId;
    private LocalDate effectiveDate;
    private String notes;
  }

  /** Close a case without a result. */
  @Getter
  @Setter
  public static class CloseRequest {
    private String comment;
  }
}
