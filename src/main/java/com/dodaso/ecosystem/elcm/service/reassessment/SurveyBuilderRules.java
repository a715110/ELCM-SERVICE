package com.dodaso.ecosystem.elcm.service.reassessment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * ADDED 2026-11-13. Plain checks of the survey builder, kept free of Spring so they are easy to
 * test. Each check gives the message to show, or null when the input is fine. The answer types
 * are the ones {@link ReassessmentAnswerRules} understands; the labels here are what the builder
 * shows to people.
 */
public final class SurveyBuilderRules {

  public static final int MAX_QUESTIONS = 60;
  public static final int MAX_CHOICES = 25;
  static final int NAME_MAX = 150;
  static final int DESCRIPTION_MAX = 500;
  static final int TEXT_MAX = 1000;
  static final int CHOICES_MAX = 1000;
  static final int CODE_MAX = 40;

  /** Answer type code to label, in the order the builder lists them. */
  private static final Map<String, String> TYPES = new LinkedHashMap<>();

  static {
    TYPES.put(ReassessmentAnswerRules.TEXT, "Short text");
    TYPES.put(ReassessmentAnswerRules.NUMBER, "Number");
    TYPES.put(ReassessmentAnswerRules.DATE, "Date");
    TYPES.put(ReassessmentAnswerRules.YES_NO, "Yes or no");
    TYPES.put(ReassessmentAnswerRules.CHOICE, "Pick one from a list");
  }

  private SurveyBuilderRules() {
  }

  public static Map<String, String> types() {
    return java.util.Collections.unmodifiableMap(TYPES);
  }

  /** Message when the name or description cannot be saved, else null. */
  public static String checkTemplate(final String name, final String description) {
    if (name == null || name.isBlank()) {
      return "Give the survey a name.";
    }
    if (name.trim().length() > NAME_MAX) {
      return "The name can have at most " + NAME_MAX + " characters.";
    }
    if (description != null && description.trim().length() > DESCRIPTION_MAX) {
      return "The description can have at most " + DESCRIPTION_MAX + " characters.";
    }
    return null;
  }

  /** Message when the question cannot be saved, else null. */
  public static String checkQuestion(final String text, final String type, final String choices) {
    if (text == null || text.isBlank()) {
      return "Write the question.";
    }
    if (text.trim().length() > TEXT_MAX) {
      return "The question can have at most " + TEXT_MAX + " characters.";
    }
    if (type == null || !TYPES.containsKey(type)) {
      return "Choose what kind of answer is expected.";
    }
    if (ReassessmentAnswerRules.CHOICE.equals(type)) {
      final List<String> list = ReassessmentAnswerRules.splitChoices(choices);
      if (list.size() < 2) {
        return "Give at least two choices, one per line.";
      }
      if (list.size() > MAX_CHOICES) {
        return "Give at most " + MAX_CHOICES + " choices.";
      }
      if (list.stream().map(s -> s.toLowerCase(Locale.ROOT)).distinct().count() != list.size()) {
        return "The same choice is listed twice.";
      }
      if (String.join("\n", list).length() > CHOICES_MAX) {
        return "The choices are too long.";
      }
    }
    return null;
  }

  /** The choices to store: one trimmed choice per line for a list question, else null. */
  public static String storedChoices(final String type, final String choices) {
    return ReassessmentAnswerRules.CHOICE.equals(type)
        ? String.join("\n", ReassessmentAnswerRules.splitChoices(choices)) : null;
  }

  /**
   * A code made from the name: capitals, digits and underscores, at most 40 characters, made
   * unique with a number when {@code taken} says the code is already used.
   */
  public static String codeFrom(final String name, final Predicate<String> taken) {
    String base = name == null ? "" : name.trim().toUpperCase(Locale.ROOT)
        .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    if (base.isEmpty()) {
      base = "SURVEY";
    }
    if (base.length() > CODE_MAX - 4) {
      base = base.substring(0, CODE_MAX - 4).replaceAll("_+$", "");
    }
    String code = base;
    int n = 2;
    while (taken.test(code)) {
      code = base + "_" + n++;
    }
    return code;
  }

  /** Position after moving {@code index} one step; the same index when it cannot move. */
  public static int movedIndex(final int index, final int size, final boolean up) {
    final int target = up ? index - 1 : index + 1;
    return target < 0 || target >= size ? index : target;
  }
}
