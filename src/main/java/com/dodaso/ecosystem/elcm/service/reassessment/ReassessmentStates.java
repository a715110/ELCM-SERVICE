package com.dodaso.ecosystem.elcm.service.reassessment;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Status codes of a reassessment case and the moves between them. The codes match rows in
 * lkp_reassessment_case_status. The table below is the one place that defines the flow; to change
 * the flow after review, change {@link #NEXT} and nothing else.
 */
public final class ReassessmentStates {

  public static final String OPEN = "OPEN";
  public static final String SURVEY_SENT = "SURVEY_SENT";
  public static final String SURVEY_RECEIVED = "SURVEY_RECEIVED";
  public static final String IN_ASSESSMENT = "IN_ASSESSMENT";
  public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
  public static final String CONCLUDED = "CONCLUDED";
  public static final String CLOSED = "CLOSED";

  /** Moves allowed from each status. */
  private static final Map<String, Set<String>> NEXT =
      Map.of(
          OPEN, Set.of(SURVEY_SENT, IN_ASSESSMENT, CLOSED),
          SURVEY_SENT, Set.of(SURVEY_RECEIVED, SURVEY_SENT, IN_ASSESSMENT, CLOSED),
          SURVEY_RECEIVED, Set.of(IN_ASSESSMENT, CLOSED),
          IN_ASSESSMENT, Set.of(AWAITING_APPROVAL, CLOSED),
          AWAITING_APPROVAL, Set.of(IN_ASSESSMENT, CONCLUDED, CLOSED),
          CONCLUDED, Set.of(CLOSED),
          CLOSED, Set.of());

  /** Statuses in display order. */
  public static final List<String> ORDER =
      List.of(OPEN, SURVEY_SENT, SURVEY_RECEIVED, IN_ASSESSMENT, AWAITING_APPROVAL, CONCLUDED,
          CLOSED);

  private ReassessmentStates() {
  }

  /** True when a case may move from one status to the other. */
  public static boolean canMove(final String from, final String to) {
    return NEXT.getOrDefault(from, Set.of()).contains(to);
  }

  /** True when the case is finished and takes no more changes. */
  public static boolean isFinal(final String status) {
    return CLOSED.equals(status);
  }

  /** True when the conclusion may still be written (before it is submitted for approval). */
  public static boolean conclusionEditable(final String status) {
    return IN_ASSESSMENT.equals(status) || SURVEY_RECEIVED.equals(status)
        || SURVEY_SENT.equals(status) || OPEN.equals(status);
  }
}
