package com.dodaso.ecosystem.elcm.service.reassessment;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** ADDED 2026-11-13. Request and row classes of the survey builder. */
public final class SurveyBuilderDtos {

  private SurveyBuilderDtos() {
  }

  /** One survey in the list. */
  @Getter
  @Setter
  public static class TemplateRow {
    private Long id;
    private String code;
    private String name;
    private String description;
    /** True when the survey can be chosen when sending (published). */
    private boolean published;
    /** Questions that would be sent. */
    private long questionCount;
    /** Times it has been sent to a respondent. */
    private long sentCount;
  }

  /** One question of a survey, as the builder shows it. */
  @Getter
  @Setter
  public static class QuestionRow {
    private Long id;
    private int sortOrder;
    private String text;
    private String type;
    private String typeLabel;
    /** One choice per line, for a list question. */
    private String choices;
    private boolean required;
    private boolean active;
  }

  /** A survey with all its questions. */
  @Getter
  @Setter
  public static class TemplateDetail {
    private TemplateRow summary;
    private List<QuestionRow> questions = new ArrayList<>();
  }

  /** Create or change the name, description and published state of a survey. */
  @Getter
  @Setter
  public static class SaveTemplateRequest {
    private String name;
    private String description;
    /** Only used when changing; a new survey always starts unpublished. */
    private Boolean published;
  }

  /** Copy a survey under a new name. */
  @Getter
  @Setter
  public static class CopyRequest {
    private String name;
  }

  /** Add or change a question. */
  @Getter
  @Setter
  public static class SaveQuestionRequest {
    private String text;
    private String type;
    private String choices;
    private boolean required = true;
    private boolean active = true;
  }

  /** Move a question one step. */
  @Getter
  @Setter
  public static class MoveRequest {
    /** UP or DOWN. */
    private String direction;
  }
}
