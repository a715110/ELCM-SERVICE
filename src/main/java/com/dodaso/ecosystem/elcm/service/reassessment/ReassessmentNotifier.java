package com.dodaso.ecosystem.elcm.service.reassessment;

/**
 * Hook for telling people about reassessment events (survey sent, conclusion waiting for approval).
 * The default only writes a log line. A later change adds an ECWS task or Teams message by adding
 * another implementation; the service does not change.
 */
public interface ReassessmentNotifier {

  /** A survey was sent to the respondent. */
  void surveySent(String caseCode, String respondent);

  /** A conclusion was submitted and waits for an approver. */
  void awaitingApproval(String caseCode);
}
