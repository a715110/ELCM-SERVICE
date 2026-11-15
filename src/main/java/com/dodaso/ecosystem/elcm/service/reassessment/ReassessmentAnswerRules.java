package com.dodaso.ecosystem.elcm.service.reassessment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Answer types and the JSON forms of a survey. The answer types live here, not in the database, so
 * a new type is one new case in {@link #check}. A survey is copied to the survey response as a JSON
 * list of {@link SurveyQuestion}; answers are stored as a JSON object keyed by question number.
 */
public final class ReassessmentAnswerRules {

  public static final String TEXT = "TEXT";
  public static final String NUMBER = "NUMBER";
  public static final String DATE = "DATE";
  public static final String YES_NO = "YES_NO";
  public static final String CHOICE = "CHOICE";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ReassessmentAnswerRules() {
  }

  /** One question as copied into a sent survey. */
  public static class SurveyQuestion {
    private int number;
    private String text;
    private String type;
    private List<String> choices = new ArrayList<>();
    private boolean required;

    public SurveyQuestion() {
    }

    public SurveyQuestion(int number, String text, String type, List<String> choices,
        boolean required) {
      this.number = number;
      this.text = text;
      this.type = type;
      this.choices = choices == null ? new ArrayList<>() : choices;
      this.required = required;
    }

    public int getNumber() {
      return number;
    }

    public void setNumber(int number) {
      this.number = number;
    }

    public String getText() {
      return text;
    }

    public void setText(String text) {
      this.text = text;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public List<String> getChoices() {
      return choices;
    }

    public void setChoices(List<String> choices) {
      this.choices = choices;
    }

    public boolean isRequired() {
      return required;
    }

    public void setRequired(boolean required) {
      this.required = required;
    }
  }

  /** Splits a choices text (one choice per line) into a list, dropping blank lines. */
  public static List<String> splitChoices(final String choices) {
    final List<String> out = new ArrayList<>();
    if (choices != null) {
      for (final String line : choices.split("\\R")) {
        if (!line.isBlank()) {
          out.add(line.trim());
        }
      }
    }
    return out;
  }

  /** Reads a survey snapshot. An empty or missing value is an empty survey. */
  public static List<SurveyQuestion> readQuestions(final String json) {
    if (json == null || json.isBlank()) {
      return new ArrayList<>();
    }
    try {
      return MAPPER.readValue(json, new TypeReference<List<SurveyQuestion>>() { });
    } catch (JsonProcessingException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
          "The saved survey could not be read.");
    }
  }

  public static String writeQuestions(final List<SurveyQuestion> questions) {
    return write(questions);
  }

  /** Reads saved answers: question number to answer text. */
  public static Map<String, String> readAnswers(final String json) {
    if (json == null || json.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
    } catch (JsonProcessingException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
          "The saved answers could not be read.");
    }
  }

  public static String writeAnswers(final Map<String, String> answers) {
    return write(answers);
  }

  /**
   * Checks the answers against the survey and returns the cleaned map (trimmed, blanks dropped).
   * 422 with the question number when an answer does not fit its type or a required one is
   * missing. Answers for numbers that are not in the survey are refused.
   */
  public static Map<String, String> check(
      final List<SurveyQuestion> questions, final Map<String, String> answers) {
    return check(questions, answers, true);
  }

  /**
   * Same as {@link #check(List, Map)}; with enforceRequired false a missing required answer is
   * accepted, which is how a respondent saves a draft.
   */
  public static Map<String, String> check(
      final List<SurveyQuestion> questions, final Map<String, String> answers,
      final boolean enforceRequired) {
    final Map<String, String> clean = new LinkedHashMap<>();
    final Map<String, String> given = answers == null ? Map.of() : answers;
    for (final String key : given.keySet()) {
      if (questions.stream().noneMatch(q -> String.valueOf(q.getNumber()).equals(key))) {
        throw invalid("There is no question " + key + " in this survey.");
      }
    }
    for (final SurveyQuestion q : questions) {
      final String key = String.valueOf(q.getNumber());
      final String raw = given.get(key);
      final String value = raw == null ? "" : raw.trim();
      if (value.isEmpty()) {
        if (enforceRequired && q.isRequired()) {
          throw invalid("Question " + key + " needs an answer.");
        }
        continue;
      }
      checkOne(q, value);
      clean.put(key, value);
    }
    return clean;
  }

  private static void checkOne(final SurveyQuestion q, final String value) {
    final String where = "Question " + q.getNumber() + ": ";
    switch (q.getType() == null ? TEXT : q.getType()) {
      case NUMBER:
        try {
          Double.parseDouble(value);
        } catch (NumberFormatException e) {
          throw invalid(where + "enter a number.");
        }
        break;
      case DATE:
        try {
          LocalDate.parse(value);
        } catch (DateTimeParseException e) {
          throw invalid(where + "enter a date as yyyy-mm-dd.");
        }
        break;
      case YES_NO:
        if (!"YES".equals(value) && !"NO".equals(value)) {
          throw invalid(where + "answer Yes or No.");
        }
        break;
      case CHOICE:
        if (!q.getChoices().contains(value)) {
          throw invalid(where + "pick one of the listed choices.");
        }
        break;
      case TEXT:
        if (value.length() > 4000) {
          throw invalid(where + "the answer is too long.");
        }
        break;
      default:
        // An unknown type is stored as text so an old survey never blocks a respondent.
        break;
    }
  }

  private static String write(final Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
          "The survey data could not be saved.");
    }
  }

  private static ResponseStatusException invalid(final String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }
}
