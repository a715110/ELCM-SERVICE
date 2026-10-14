package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One document of a package the signed-in person submitted whose values were applied to the
 * contract record (committed). appliedAt is when the submission was applied, UTC, ISO.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MyCommittedDocument implements Serializable {
  private Long stagedDocumentId;
  private Long fileUploadId;
  private String fileName;
  private String roleLabel;
  private Long packageId;
  private String packageCode;
  private String workspace;
  private String targetRecord;
  private String appliedAt;
}
