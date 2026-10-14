package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One submission made by the signed-in person, for the My Submissions screen.
 *
 * <p>groupCode is the filter bucket: PENDING (waiting to be picked up), IN_PROGRESS (extracting, in
 * review, awaiting approval, or failed and waiting for a person), COMPLETED (applied to the
 * contract record) or DECLINED (sent back to Assembly by a reviewer). Times are UTC, ISO.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MySubmissionItem implements Serializable {
  private Long submissionId;
  private Long packageId;
  private String packageCode;
  private String workspace;
  private String targetRecord;
  private int docCount;
  private String statusCode;
  private String statusLabel;
  private String groupCode;
  private String submittedAt;
  private String updatedAt;
  private String correctionNote;
  private String declineReason;
  private String declinedBy;
  private String declinedAt;
}
