package com.dodaso.ecosystem.elcm.service.extraction;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What the screen shows while a package is being extracted. state is one of NOT_SUBMITTED,
 * QUEUED (waiting for its turn, queuePosition says which), RUNNING, DONE (ready for review) or
 * FAILED (a person must look; Retry is allowed). Times are UTC.
 */
public record ExtractionStatus(
    Long packageId,
    Long submissionId,
    String state,
    String message,
    Integer queuePosition,
    int documentsTotal,
    int documentsDone,
    int documentsFailed,
    int needsReviewTotal,
    List<DocumentProgress> documents) {

    public record DocumentProgress(
        Long stagedDocumentId,
        Long fileUploadId,
        String fileName,
        String status,
        int attempts,
        LocalDateTime startedAt,
        Integer elapsedSeconds,
        Integer needsReviewCount,
        String errorMessage) {

        public DocumentProgress withFileName(final String name) {
            return new DocumentProgress(stagedDocumentId, fileUploadId, name, status, attempts, startedAt,
                elapsedSeconds, needsReviewCount, errorMessage);
        }
    }
}
