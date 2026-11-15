package com.dodaso.ecosystem.elcm.service.reassessment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Default notifier: logs only. Another implementation marked @Primary replaces it. */
@Slf4j
@Component
public class LoggingReassessmentNotifier implements ReassessmentNotifier {

  @Override
  public void surveySent(final String caseCode, final String respondent) {
    log.info("Reassessment {}: survey sent to {}", caseCode, respondent);
  }

  @Override
  public void awaitingApproval(final String caseCode) {
    log.info("Reassessment {}: conclusion waits for approval", caseCode);
  }
}
