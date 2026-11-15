package com.dodaso.ecosystem.elcm.security;

/**
 * Catalog of ELCM permissions. Naming scheme: {@code area:action} in lower case, for example
 * {@code pipeline:view}. Each new feature adds its permissions here as it is built; the role and
 * permission editor planned for later is fed from this list.
 */
public enum ElcmPermission {

  /**
   * See staged documents and pipeline metrics.
   */
  PIPELINE_VIEW("pipeline:view"),

  /**
   * Add documents to the pipeline (Upload Files, Add to Pipeline).
   */
  PIPELINE_SUBMIT("pipeline:submit"),

  /**
   * Delete (soft delete) a staged document. Ownership is checked separately, in the service.
   */
  PIPELINE_DELETE("pipeline:delete"),

  /**
   * Review extracted values: correct a value, confirm fields, complete the review. Kept apart from
   * pipeline:submit on purpose, so the people who submit documents are not the people who confirm
   * what was extracted from them (separation of duties).
   */
  PIPELINE_REVIEW("pipeline:review"),

  /**
   * Approve a completed review and apply the values to the contract record, or send it back to the
   * reviewer. Held by the Approver role, not by reviewers or submitters (separation of duties:
   * whoever submitted or reviewed a package cannot approve it).
   */
  PIPELINE_APPROVE("pipeline:approve"),

  /**
   * Set or change the role of a document in a package (lease, amendment, schedule and so on),
   * before submit or while the package is being reviewed. Held by the submitting roles and by
   * reviewers, not by approvers.
   */
  PIPELINE_CLASSIFY("pipeline:classify"),
  /**
   * Create, edit, publish and compare extraction template versions. Held by System Admin and Super
   * Admin only (they get every permission in ElcmRolePermissions).
   */
  TEMPLATE_MANAGE("template:manage"),

  /** See reassessment cases (ADDED 2026-11-11). */
  REASSESSMENT_VIEW("reassessment:view"),

  /** Open a reassessment, send its survey, link a replacement, close it. */
  REASSESSMENT_MANAGE("reassessment:manage"),

  /** Write and submit the conclusion of a reassessment. */
  REASSESSMENT_ASSESS("reassessment:assess"),

  /** Approve or reject a submitted conclusion. */
  REASSESSMENT_APPROVE("reassessment:approve"),

  /**
   * ADDED 2026-11-13. Create, edit, publish and delete the surveys used by reassessments. Held by
   * System Admin and Super Admin only (they get every permission in ElcmRolePermissions).
   */
  SURVEY_ADMIN("survey:admin");

  private final String code;

  ElcmPermission(String code) {
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}