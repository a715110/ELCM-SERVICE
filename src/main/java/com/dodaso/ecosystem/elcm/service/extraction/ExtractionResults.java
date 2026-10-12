package com.dodaso.ecosystem.elcm.service.extraction;

import java.util.List;

/** The extracted values of a package, per document, for the reviewer. */
public record ExtractionResults(Long packageId, List<DocumentResult> documents) {

    public record DocumentResult(
        Long stagedDocumentId,
        Long fileUploadId,
        String fileName,
        String jobStatus,
        String model,
        int needsReviewCount,
        List<FieldRow> fields) {

        public DocumentResult withFileName(final String name) {
            return new DocumentResult(stagedDocumentId, fileUploadId, name, jobStatus, model, needsReviewCount, fields);
        }
    }

    public record FieldRow(
        String key,
        String label,
        String dataType,
        boolean critical,
        boolean required,
        String value,
        String correctedValue,
        String status,
        String quote,
        String problem,
        String note,
        String candidates,
        boolean needsReview,
        boolean verified) {
    }
}
