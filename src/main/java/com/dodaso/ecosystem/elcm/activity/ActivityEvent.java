package com.dodaso.ecosystem.elcm.activity;

import lombok.Getter;

/**
 * ADDED 2026-10-20 -- the package events that go into the Activity Log. Each one has a template in
 * EARS (ears_activity_log.sql): template name "ELCM Activity: " plus the name below. The label is
 * the short title shown in the log; the message is written by the caller.
 */
@Getter
public enum ActivityEvent {
    PACKAGE_CREATED("Package Created", "Package created"),
    DOCUMENTS_ADDED("Documents Added", "Documents added"),
    DOCUMENT_REMOVED("Document Removed", "Document removed"),
    DOCUMENT_ROLE_SET("Document Role Set", "Document role changed"),
    ASSIGNEE_CHANGED("Assignee Changed", "Assignee changed"),
    PACKAGE_SUBMITTED("Package Submitted", "Package submitted"),
    PACKAGE_UNSUBMITTED("Package Unsubmitted", "Package unsubmitted"),
    EXTRACTION_STARTED("Extraction Started", "Extraction started"),
    EXTRACTION_COMPLETED("Extraction Completed", "Extraction completed"),
    EXTRACTION_FAILED("Extraction Failed", "Extraction failed"),
    EXTRACTION_RETRIED("Extraction Retried", "Extraction retried"),
    EXTRACTION_STOPPED("Extraction Stopped", "Extraction stopped"),
    DOCUMENT_REPROCESSED("Document Reprocessed", "Document sent to be read again"),
    FIELD_SAVED("Field Saved", "Field saved"),
    FIELDS_CONFIRMED("Fields Confirmed", "Fields confirmed"),
    REVIEW_COMPLETED("Review Completed", "Review completed"),
    REVIEW_REOPENED("Review Reopened", "Review reopened"),
    SENT_BACK("Sent Back", "Sent back to the reviewer"),
    PACKAGE_DECLINED("Package Declined", "Package declined"),
    VALUES_APPLIED("Values Applied", "Values applied to the contract record");

  private static final String TEMPLATE_PREFIX = "ELCM Activity: ";

  private final String templateName;
  private final String label;

  ActivityEvent(final String name, final String label) {
    this.templateName = TEMPLATE_PREFIX + name;
    this.label = label;
  }
}
