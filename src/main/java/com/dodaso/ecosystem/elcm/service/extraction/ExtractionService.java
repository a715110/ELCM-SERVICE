package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import com.dodaso.ecosystem.elcm.service.pipeline.FileUploadLookupService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Public face of extraction for the controller: progress, results, retry. Reads come from
 * ExtractionReadService (database), then file names are added from common-service outside any
 * transaction, the same split ContractPackageService uses.
 */
@Service
@RequiredArgsConstructor
public class ExtractionService {

    private final ExtractionReadService readService;
    private final ExtractionStore store;
    private final ReviewStore reviewStore;
    private final ApplyStore applyStore;
    private final FileUploadLookupService fileUploadLookupService;

    public ExtractionStatus status(final Long packageId) {
        final ExtractionStatus status = readService.status(packageId);
        if (status.documents().isEmpty()) {
            return status;
        }
        final Map<Long, FileUploadDTO> names = fileUploadLookupService.findByIds(
            idsOf(status.documents().stream().map(ExtractionStatus.DocumentProgress::fileUploadId).toList()));
        final List<ExtractionStatus.DocumentProgress> named = status.documents().stream()
            .map(d -> d.withFileName(nameOf(names, d.fileUploadId()))).toList();
        return new ExtractionStatus(status.packageId(), status.submissionId(), status.state(), status.message(),
            status.queuePosition(), status.documentsTotal(), status.documentsDone(), status.documentsFailed(),
            status.needsReviewTotal(), named);
    }

    public ExtractionResults results(final Long packageId) {
        final ExtractionResults results = readService.results(packageId);
        if (results.documents().isEmpty()) {
            return results;
        }
        final Map<Long, FileUploadDTO> names = fileUploadLookupService.findByIds(
            idsOf(results.documents().stream().map(ExtractionResults.DocumentResult::fileUploadId).toList()));
        return new ExtractionResults(results.packageId(), results.documents().stream()
            .map(d -> d.withFileName(nameOf(names, d.fileUploadId()))).toList());
    }

    /** Names of the package's documents that cannot be read because their contract type has no template. */
    public ExtractionCoverage coverage(final Long packageId) {
        final List<Long> fileIds = readService.documentsWithoutTemplate(packageId);
        if (fileIds.isEmpty()) {
            return new ExtractionCoverage(List.of());
        }
        final Map<Long, FileUploadDTO> names = fileUploadLookupService.findByIds(idsOf(fileIds));
        final List<String> files = new java.util.ArrayList<>();
        for (final Long fileId : fileIds) {
            files.add(nameOf(names, fileId));
        }
        return new ExtractionCoverage(files);
    }

    /** Failed extraction back to the queue; only the failed documents run again. */
    public void retry(final Long packageId) {
        store.requeueFailed(packageId);
    }

    /** Reviewer: correct and/or confirm one field. */
    public void saveField(final Long packageId, final Long resultId, final String correctedValue,
                          final boolean verified, final String caller) {
        reviewStore.saveField(packageId, resultId, correctedValue, verified, caller);
    }

    /** Reviewer: confirm every field the extractor verified. Returns how many were confirmed. */
    public int confirmVerified(final Long packageId, final String caller) {
        return reviewStore.confirmVerified(packageId, caller);
    }

    /** Reviewer: complete the review (422 while critical or flagged fields are unconfirmed). */
    public void completeReview(final Long packageId, final String caller) {
        reviewStore.complete(packageId, caller);
    }

    /** What the reviewer did to one field, oldest first. */
    public FieldHistory history(final Long packageId, final Long resultId) {
        return reviewStore.history(packageId, resultId);
    }

    /** Reviewer: write the reviewed values to the package's target contract record. */
    public ApplyResult apply(final Long packageId, final Long targetRecordId, final String caller) {
        return applyStore.apply(packageId, targetRecordId, caller);
    }

    /** Reviewer: the records a package without a target can be applied to. */
    public List<TargetCandidate> targetCandidates(final Long packageId) {
        return applyStore.candidates(packageId);
    }

    /** Reviewer: reopen a completed review so values can be changed again. */
    public void reopenReview(final Long packageId, final String caller) {
        reviewStore.reopen(packageId, caller);
    }

    /** Approver: send a completed review back to the reviewer, with a reason. */
    public void sendBack(final Long packageId, final String reason, final String caller) {
        reviewStore.sendBack(packageId, reason, caller);
    }

    /** The reasons the package's review was sent back, newest first. */
    public List<SendBackEntry> sendBacks(final Long packageId) {
        return reviewStore.sendBacks(packageId);
    }

    /** Reviewer: decline the package back to Assembly with a reason. */
    public void decline(final Long packageId, final String reason, final String caller) {
        reviewStore.decline(packageId, reason, caller);
    }

    private static Set<Long> idsOf(final List<Long> ids) {
        return new HashSet<>(ids);
    }

    private static String nameOf(final Map<Long, FileUploadDTO> names, final Long id) {
        final FileUploadDTO dto = names.get(id);
        return dto != null ? dto.getFileName() : "File " + id;
    }
}