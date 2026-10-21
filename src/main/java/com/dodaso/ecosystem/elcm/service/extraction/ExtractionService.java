package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import com.dodaso.ecosystem.elcm.activity.ActivityEvent;
import com.dodaso.ecosystem.elcm.activity.ActivityRecorder;
import com.dodaso.ecosystem.elcm.service.pipeline.ContractPackageService;
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
    private final ExtractionWorker worker;
    /** ADDED 2026-10-20: the package Activity Log (best effort, after the work is committed). */
    private final ActivityRecorder activityRecorder;
    private final ContractPackageService contractPackageService;

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
        worker.wake(); // ADDED 2026-10-15 -- start now instead of at the next scheduled poll
        activityRecorder.record(packageId, ActivityEvent.EXTRACTION_RETRIED,
            "Extraction was queued to run again.");
    }

    /** Reviewer: correct and/or confirm one field. */
    public void saveField(final Long packageId, final Long resultId, final String correctedValue,
                          final boolean verified, final String reason, final String caller) {
        reviewStore.saveField(packageId, resultId, correctedValue, verified, reason, caller);
        // ADDED 2026-10-20 -- Activity Log. The field name is looked up later, off the request.
        final boolean corrected = correctedValue != null && !correctedValue.isBlank();
        activityRecorder.recordLater(packageId, ActivityEvent.FIELD_SAVED, () -> {
            String label = "A field";
            try {
                label = "Field \"" + reviewStore.history(packageId, resultId).label() + "\"";
            } catch (final RuntimeException e) {
                // keep the neutral wording
            }
            final String what = (corrected ? "value corrected" : "value left as read")
                + (verified ? ", confirmed" : ", not confirmed");
            return label + ": " + what
                + (reason != null && !reason.isBlank() ? ". Reason: " + reason.trim() : ".");
        });
    }

    /** Reviewer: send one document back to be read again, with a reason. */
    public void reprocess(final Long packageId, final Long stagedDocumentId, final String reason,
                          final String caller) {
        reviewStore.reprocess(packageId, stagedDocumentId, reason, caller);
        worker.wake(); // ADDED 2026-10-15 -- start now instead of at the next scheduled poll
        activityRecorder.recordLater(packageId, ActivityEvent.DOCUMENT_REPROCESSED,
            () -> contractPackageService.namesOf(packageId, Set.of(stagedDocumentId))
                + " was sent to be read again."
                + (reason != null && !reason.isBlank() ? " Reason: " + reason.trim() : ""));
    }

    /** Reviewer: confirm every field the extractor verified. Returns how many were confirmed. */
    public int confirmVerified(final Long packageId, final String caller) {
        final int confirmed = reviewStore.confirmVerified(packageId, caller);
        activityRecorder.record(packageId, ActivityEvent.FIELDS_CONFIRMED, caller,
            confirmed + " verified field" + (confirmed == 1 ? " was" : "s were") + " confirmed.");
        return confirmed;
    }

    /** Reviewer: complete the review (422 while critical or flagged fields are unconfirmed). */
    public void completeReview(final Long packageId, final String caller) {
        reviewStore.complete(packageId, caller);
        activityRecorder.record(packageId, ActivityEvent.REVIEW_COMPLETED, caller,
            "The review was completed.");
    }

    /** What the reviewer did to one field, oldest first. */
    public FieldHistory history(final Long packageId, final Long resultId) {
        return reviewStore.history(packageId, resultId);
    }

    /** Reviewer: write the reviewed values to the package's target contract record. */
    public ApplyResult apply(final Long packageId, final Long targetRecordId, final String caller) {
        final ApplyResult result = applyStore.apply(packageId, targetRecordId, caller);
        activityRecorder.record(packageId, ActivityEvent.VALUES_APPLIED, caller,
            "Reviewed values were applied to contract record "
                + (result.recordCode() != null ? result.recordCode() : "") + ": "
                + result.written() + " written, " + result.unchanged() + " unchanged.");
        return result;
    }

    /** Reviewer: the records a package without a target can be applied to. */
    public List<TargetCandidate> targetCandidates(final Long packageId) {
        return applyStore.candidates(packageId);
    }

    /** Reviewer: reopen a completed review so values can be changed again. */
    public void reopenReview(final Long packageId, final String caller) {
        reviewStore.reopen(packageId, caller);
        activityRecorder.record(packageId, ActivityEvent.REVIEW_REOPENED, caller,
            "The completed review was reopened.");
    }

    /** Approver: send a completed review back to the reviewer, with a reason. */
    public void sendBack(final Long packageId, final String reason, final String caller) {
        reviewStore.sendBack(packageId, reason, caller);
        activityRecorder.record(packageId, ActivityEvent.SENT_BACK, caller,
            "The review was sent back to the reviewer."
                + (reason != null && !reason.isBlank() ? " Reason: " + reason.trim() : ""));
    }

    /** The reasons the package's review was sent back, newest first. */
    public List<SendBackEntry> sendBacks(final Long packageId) {
        return reviewStore.sendBacks(packageId);
    }

    /** The correction note of a resubmission and the decline it answers. */
    public Resubmission resubmission(final Long packageId) {
        return reviewStore.resubmission(packageId);
    }

    /** Reviewer: decline the package back to Assembly with a reason. */
    public void decline(final Long packageId, final String reason, final String caller) {
        reviewStore.decline(packageId, reason, caller);
        activityRecorder.record(packageId, ActivityEvent.PACKAGE_DECLINED, caller,
            "The package was declined back to Assembly."
                + (reason != null && !reason.isBlank() ? " Reason: " + reason.trim() : ""));
    }

    private static Set<Long> idsOf(final List<Long> ids) {
        return new HashSet<>(ids);
    }

    private static String nameOf(final Map<Long, FileUploadDTO> names, final Long id) {
        final FileUploadDTO dto = names.get(id);
        return dto != null ? dto.getFileName() : "File " + id;
    }
}