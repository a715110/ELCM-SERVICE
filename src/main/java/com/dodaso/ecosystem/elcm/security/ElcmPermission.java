package com.dodaso.ecosystem.elcm.security;

/**
 * Catalog of ELCM permissions. Naming scheme: {@code area:action} in lower case, for example
 * {@code pipeline:view}. Each new feature adds its permissions here as it is built; the role and
 * permission editor planned for later is fed from this list.
 */
public enum ElcmPermission {

  /** See staged documents and pipeline metrics. */
  PIPELINE_VIEW("pipeline:view"),

  /** Add documents to the pipeline (Upload Files, Add to Pipeline). */
  PIPELINE_SUBMIT("pipeline:submit"),

  /** Delete (soft delete) a staged document. Ownership is checked separately, in the service. */
  PIPELINE_DELETE("pipeline:delete"),

  /**
   * Review extracted values: correct a value, confirm fields, complete the review. Kept apart from
   * pipeline:submit on purpose, so the people who submit documents are not the people who confirm
   * what was extracted from them (separation of duties).
   */
  PIPELINE_REVIEW("pipeline:review");

  private final String code;

  ElcmPermission(String code) {
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
