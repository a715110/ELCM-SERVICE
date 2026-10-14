package com.dodaso.ecosystem.elcm.service.extraction;

/**
 * What a reviewer needs to know when the package was submitted again after a decline: the
 * submitter's correction note and the reason it was declined. resubmitted is false (and the rest
 * null) for a first submission. Times are UTC, ISO.
 */
public record Resubmission(
    boolean resubmitted,
    String correctionNote,
    String submittedBy,
    String submittedAt,
    String previousDeclineReason,
    String previousDeclinedBy,
    String previousDeclinedAt) {}
