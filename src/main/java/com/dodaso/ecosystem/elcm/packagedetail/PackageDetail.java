package com.dodaso.ecosystem.elcm.packagedetail;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ADDED 2026-10-25 -- everything the Package Details screen shows about one package.
 *
 * <p>statusCode is ASSEMBLY (draft), VALIDATED (submitted) or COMPLETED (closed); statusLabel is
 * the display text. relation is ASSIGNED, CREATED, SUBMITTER, REVIEWER or OTHER: how the viewer is
 * involved. Times are ISO, UTC, like the other times elcm-service sends. Names are display names
 * when they can be found, else the login id.
 */
@Getter
@Setter
@NoArgsConstructor
public class PackageDetail {

  private Long packageId;
  private String packageCode;
  private String statusCode;
  private String statusLabel;
  private String workspaceCode;
  private String workspaceName;
  private String targetRecord;
  private String assigneeLoginId;
  private String assignee;
  private String createdBy;
  private String createdAt;
  private String updatedBy;
  private String updatedAt;
  private String relation;
  private List<Document> documents = new ArrayList<>();
  private List<Submission> submissions = new ArrayList<>();

  /** One document of the package; fileUploadId opens it in the document viewer. */
  @Getter
  @Setter
  @NoArgsConstructor
  public static class Document {

    private Long stagedDocumentId;
    private Long fileUploadId;
    private String fileName;
    private String roleCode;
    private String roleLabel;
    private String targetRecord;
  }

  /** One submission of the package (a package can be submitted again after a decline). */
  @Getter
  @Setter
  @NoArgsConstructor
  public static class Submission {

    private Long submissionId;
    private String statusCode;
    private String statusLabel;
    private String submittedBy;
    private String submittedAt;
    private String claimedBy;
    private String reviewedBy;
    private String reviewedAt;
    private String declineReason;
    private String declinedBy;
    private String declinedAt;
    private String correctionNote;
  }
}
