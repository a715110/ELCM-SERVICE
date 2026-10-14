package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line of the work queue: a submitted package with the state of its newest submission.
 *
 * <p>stageCode is the bucket the queue tabs use: EXTRACTING, REVIEW, APPROVAL, FAILED or APPLIED.
 * The two display-name fields are filled by {@link WorkQueueService} after the database read, so
 * the transaction never waits on IAMS.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WorkQueueItem implements Serializable {
  private Long packageId;
  private String packageCode;
  private String workspace;
  private String targetRecord;
  private int docCount;
  private String assignee;
  private String assigneeLoginId;
  private String submittedBy;
  private String submittedByLoginId;
  private String reviewedBy;
  private String submissionStatusCode;
  private String stageCode;
  private String stageLabel;

  /** ISO time the submission entered its current state (its last update), UTC. */
  private String since;

  /** ISO time the package was submitted, UTC. */
  private String submittedAt;
}
