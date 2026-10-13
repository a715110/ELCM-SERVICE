package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionFieldResult;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionFieldRevision;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionJob;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import com.dodaso.ecosystem.elcm.entity.pipeline.PackageDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionFieldResultRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionFieldRevisionRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionJobRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpExtractionJobStatusRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpPackageStatusRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpStagedDocumentStatusRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpSubmissionStatusRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractPackageRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.StagedDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The reviewer's writes (ADDED 2026-10-12): correct a value, confirm a field, confirm everything
 * the extractor already verified, and complete the review. Each method is one transaction.
 *
 * Rules (status codes the REST layer returns):
 *   404 unknown package or field result, or a field that does not belong to the package.
 *   409 the package's newest submission is not EXTRACTED (still running, failed, or already reviewed).
 *   400 a corrected value that does not fit the field's data type (ReviewRules).
 *   422 Complete while fields still need confirming: every critical field, and every field the
 *       extractor flagged for review, must be confirmed (verified_flag) first. This is the rule in
 *       the schema comment on extraction_field_result (SDS 4.3).
 * Decline (ADDED 2026-10-12) sends the package back to Assembly with a reason; see decline().
 * Every change is also appended to extraction_field_revision (who, when, before, after), so a
 * field corrected several times keeps its whole history; extraction_field_result keeps only the
 * current state. Saving without a change records nothing.
 * Completing sets the submission to REVIEWED and nothing else: writing the confirmed values to the
 * contract record is a separate step that is not built yet.
 */
@Service
@RequiredArgsConstructor
public class ReviewStore {

    public static final String SUBMISSION_REVIEWED = "REVIEWED";
    public static final String SUBMISSION_DECLINED = "DECLINED";
    public static final int MAX_REASON_LENGTH = 1000;

    private static final String ACTION_CORRECTED = "CORRECTED";
    private static final String ACTION_CONFIRMED = "CONFIRMED";
    private static final String ACTION_UNCONFIRMED = "UNCONFIRMED";
    private static final String ACTION_CONFIRM_ALL = "CONFIRM_ALL";

    private static final String PACKAGE_VALIDATED = "VALIDATED";
    private static final String PACKAGE_ASSEMBLY = "ASSEMBLY";
    /** The status every document starts in; Unsubmit and Decline put documents back to it. */
    private static final String STAGED_DEFAULT = "UPLOADING";

    private final SubmissionRepository submissionRepository;
    private final LkpSubmissionStatusRepository lkpSubmissionStatusRepository;
    private final PackageDocumentRepository packageDocumentRepository;
    private final ExtractionJobRepository jobRepository;
    private final ExtractionFieldResultRepository resultRepository;
    private final ContractPackageRepository contractPackageRepository;
    private final LkpPackageStatusRepository lkpPackageStatusRepository;
    private final StagedDocumentRepository stagedDocumentRepository;
    private final LkpStagedDocumentStatusRepository lkpStagedDocumentStatusRepository;
    private final LkpExtractionJobStatusRepository lkpJobStatusRepository;
    private final ExtractionFieldRevisionRepository revisionRepository;

    /**
     * Saves one field: the corrected value (blank clears it) and whether the reviewer confirms it.
     * Confirming records who and when; un-confirming clears both.
     */
    @Transactional
    public void saveField(final Long packageId, final Long resultId, final String correctedValue,
                          final boolean verified, final String caller) {
        final Submission submission = reviewableSubmission(packageId);
        final ExtractionFieldResult result = resultRepository.findById(resultId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field not found"));
        if (!latestJobIds(packageId).contains(result.getJob().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Field is not part of this package");
        }
        final String typeCode = result.getTemplateField().getDataType().getCode();
        final String value = ReviewRules.normalize(typeCode, correctedValue);
        final String error = ReviewRules.validate(typeCode, value);
        if (error != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
        }
        final String beforeEffective = effective(result);
        final boolean valueChanged = !java.util.Objects.equals(result.getCorrectedValue(), value);
        final boolean wasVerified = Boolean.TRUE.equals(result.getVerifiedFlag());
        result.setCorrectedValue(value);
        applyVerified(result, verified, caller);
        resultRepository.save(result);
        if (valueChanged || wasVerified != verified) {
            final String action = valueChanged ? ACTION_CORRECTED : (verified ? ACTION_CONFIRMED : ACTION_UNCONFIRMED);
            record(result, beforeEffective, action, caller);
        }
        submissionRepository.touch(submission.getId(), now());
    }

    /**
     * Confirms every field the extractor itself verified (status verified or inferred_absent) that
     * the reviewer has not confirmed yet and that is not flagged for review. Returns how many.
     */
    @Transactional
    public int confirmVerified(final Long packageId, final String caller) {
        reviewableSubmission(packageId);
        int changed = 0;
        for (final ExtractionFieldResult r : allResults(packageId)) {
            final String status = r.getFieldStatus();
            final boolean machineSure = "verified".equals(status) || "inferred_absent".equals(status);
            if (machineSure && !Boolean.TRUE.equals(r.getNeedsReviewFlag())
                && !Boolean.TRUE.equals(r.getVerifiedFlag())) {
                applyVerified(r, true, caller);
                resultRepository.save(r);
                record(r, effective(r), ACTION_CONFIRM_ALL, caller);
                changed++;
            }
        }
        return changed;
    }

    /** Completes the review: 422 while any critical or flagged field is unconfirmed. */
    @Transactional
    public void complete(final Long packageId) {
        final Submission submission = reviewableSubmission(packageId);
        int open = 0;
        for (final ExtractionFieldResult r : allResults(packageId)) {
            final boolean mustConfirm = Boolean.TRUE.equals(r.getTemplateField().getIsCritical())
                || Boolean.TRUE.equals(r.getNeedsReviewFlag());
            if (mustConfirm && !Boolean.TRUE.equals(r.getVerifiedFlag())) {
                open++;
            }
        }
        if (open > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                open + " field(s) still need to be confirmed (critical fields and fields marked Review).");
        }
        final int moved = submissionRepository.moveStatus(submission.getId(), submission.getStatus(),
            lkpSubmissionStatusRepository.findByCode(SUBMISSION_REVIEWED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Missing lkp_submission_status row: REVIEWED (run review_status.sql)")),
            now());
        if (moved != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The review was already completed");
        }
    }

    /**
     * Reopens a completed review (ADDED 2026-10-12): the newest submission goes from REVIEWED back
     * to EXTRACTED, so the reviewer can change values again, complete the review again or decline.
     * Values, confirmations and history are kept. Who and when are recorded on the submission.
     * 404 no submission; 409 the submission is not REVIEWED (not completed yet, or already applied
     * or declined).
     */
    @Transactional
    public void reopen(final Long packageId, final String caller) {
        final Submission submission = submissionRepository.findByContractPackage_Id(packageId).stream()
            .max(Comparator.comparing(Submission::getId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The package has no submission"));
        if (!SUBMISSION_REVIEWED.equals(submission.getStatus().getCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only a package whose review is completed and not yet applied can be reopened");
        }
        final int moved = submissionRepository.reopen(submission.getId(), submission.getStatus(),
            lkpSubmissionStatusRepository.findByCode(ExtractionStore.SUBMISSION_EXTRACTED)
                .orElseThrow(() -> seedMissing("lkp_submission_status", ExtractionStore.SUBMISSION_EXTRACTED)),
            caller, now());
        if (moved != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The review was changed by someone else");
        }
    }

    /**
     * Declines the package back to Assembly (ADDED 2026-10-12). Allowed while the newest submission
     * is EXTRACTED or FAILED. The reason is required (400 when blank or over 1000 characters). Then,
     * in one transaction: the submission becomes DECLINED with reason, who and when (kept as
     * history); the package returns VALIDATED to ASSEMBLY; its documents return SUBMITTED to
     * UPLOADING; and the completed extraction jobs become SUPERSEDED, so submitting again extracts
     * every document afresh instead of reusing results the reviewer rejected. 404 no submission,
     * 409 the submission is not EXTRACTED or FAILED, or the package is not VALIDATED.
     */
    @Transactional
    public void decline(final Long packageId, final String reason, final String caller) {
        final Submission submission = submissionRepository.findByContractPackage_Id(packageId).stream()
            .max(Comparator.comparing(Submission::getId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The package has no submission"));
        final String code = submission.getStatus().getCode();
        if (!ExtractionStore.SUBMISSION_EXTRACTED.equals(code) && !ExtractionStore.SUBMISSION_FAILED.equals(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only a package whose extraction has finished or failed can be declined");
        }
        final String text = ReviewRules.normalize(reason);
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required");
        }
        if (text.length() > MAX_REASON_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "The reason is longer than " + MAX_REASON_LENGTH + " characters");
        }
        final ContractPackage pkg = submission.getContractPackage();
        if (!PACKAGE_VALIDATED.equals(pkg.getStatus().getCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The package is not submitted");
        }
        // Jobs first: latestJobs() reads the completed jobs of the package's documents.
        final var superseded = lkpJobStatusRepository.findByCode(ExtractionStore.JOB_SUPERSEDED)
            .orElseThrow(() -> seedMissing("lkp_extraction_job_status", ExtractionStore.JOB_SUPERSEDED));
        for (final ExtractionJob job : latestJobs(packageId)) {
            job.setStatus(superseded);
            jobRepository.save(job);
        }
        final var start = lkpStagedDocumentStatusRepository.findByCode(STAGED_DEFAULT)
            .orElseThrow(() -> seedMissing("lkp_staged_document_status", STAGED_DEFAULT));
        for (final PackageDocument link : packageDocumentRepository.findDetailedByPackageId(packageId)) {
            link.getStagedDocument().setStatus(start);
            stagedDocumentRepository.save(link.getStagedDocument());
        }
        pkg.setStatus(lkpPackageStatusRepository.findByCode(PACKAGE_ASSEMBLY)
            .orElseThrow(() -> seedMissing("lkp_package_status", PACKAGE_ASSEMBLY)));
        contractPackageRepository.save(pkg);

        submission.setStatus(lkpSubmissionStatusRepository.findByCode(SUBMISSION_DECLINED)
            .orElseThrow(() -> seedMissing("lkp_submission_status", SUBMISSION_DECLINED)));
        submission.setDeclineReason(text);
        submission.setDeclinedBy(caller);
        submission.setDeclinedAt(now());
        submissionRepository.save(submission);
    }

    private static ResponseStatusException seedMissing(final String table, final String code) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
            "Missing " + table + " row: " + code + " (run decline_status.sql)");
    }

    /**
     * The history of one field (any caller who can view the package). 404 when the field is not
     * part of the package's current extraction.
     */
    @Transactional(readOnly = true)
    public FieldHistory history(final Long packageId, final Long resultId) {
        final ExtractionFieldResult result = resultRepository.findById(resultId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field not found"));
        if (!latestJobIds(packageId).contains(result.getJob().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Field is not part of this package");
        }
        final List<FieldHistory.Revision> rows = new ArrayList<>();
        for (final ExtractionFieldRevision r : revisionRepository.findByFieldResult_IdOrderByRevisionNoAsc(resultId)) {
            rows.add(new FieldHistory.Revision(r.getRevisionNo(), r.getAction(), r.getPreviousValue(),
                r.getNewValue(), Boolean.TRUE.equals(r.getConfirmedFlag()), r.getChangedBy(),
                r.getChangedAt() == null ? null : r.getChangedAt().toString(),
                r.getReason()));
        }
        return new FieldHistory(resultId, result.getTemplateField().getLabel(), result.getExtractedValue(), rows);
    }

    /** The value that counts: the corrected one when there is one, otherwise what the extractor found. */
    static String effective(final ExtractionFieldResult r) {
        return r.getCorrectedValue() != null ? r.getCorrectedValue() : r.getExtractedValue();
    }

    /** Appends one revision. previous is the effective value before the change; the new one is read from the result. */
    private void record(final ExtractionFieldResult r, final String previous, final String action,
                        final String caller) {
        final ExtractionFieldRevision rev = new ExtractionFieldRevision();
        rev.setFieldResult(r);
        rev.setRevisionNo(revisionRepository.maxRevisionNo(r.getId()) + 1);
        rev.setPreviousValue(previous);
        rev.setNewValue(effective(r));
        rev.setConfirmedFlag(Boolean.TRUE.equals(r.getVerifiedFlag()));
        rev.setAction(action);
        rev.setChangedBy(caller);
        rev.setChangedAt(now());
        revisionRepository.save(rev);
    }

    private static void applyVerified(final ExtractionFieldResult r, final boolean verified, final String caller) {
        r.setVerifiedFlag(verified);
        r.setVerifiedBy(verified ? caller : null);
        r.setVerifiedAt(verified ? now() : null);
    }

    private Submission reviewableSubmission(final Long packageId) {
        final Optional<Submission> newest = submissionRepository.findByContractPackage_Id(packageId).stream()
            .max(Comparator.comparing(Submission::getId));
        if (newest.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The package has no submission");
        }
        if (!ExtractionStore.SUBMISSION_EXTRACTED.equals(newest.get().getStatus().getCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The package can be reviewed only after its extraction has finished and before the review is completed");
        }
        return newest.get();
    }

    /** The newest COMPLETED job per document of the package. */
    private List<ExtractionJob> latestJobs(final Long packageId) {
        final Set<Long> docIds = new HashSet<>();
        for (final PackageDocument link : packageDocumentRepository.findDetailedByPackageId(packageId)) {
            docIds.add(link.getStagedDocument().getId());
        }
        final Map<Long, ExtractionJob> latest = new HashMap<>();
        if (!docIds.isEmpty()) {
            for (final ExtractionJob job : jobRepository.findDetailedByStagedDocumentIds(docIds)) {
                latest.put(job.getStagedDocument().getId(), job);
            }
        }
        final List<ExtractionJob> completed = new ArrayList<>();
        for (final ExtractionJob job : latest.values()) {
            if (ExtractionStore.JOB_COMPLETED.equals(job.getStatus().getCode())) {
                completed.add(job);
            }
        }
        return completed;
    }

    private Set<Long> latestJobIds(final Long packageId) {
        final Set<Long> ids = new HashSet<>();
        for (final ExtractionJob job : latestJobs(packageId)) {
            ids.add(job.getId());
        }
        return ids;
    }

    /** Package private: ApplyStore reads the same results the reviewer saw. */
    List<ExtractionFieldResult> allResults(final Long packageId) {
        final List<ExtractionFieldResult> all = new ArrayList<>();
        for (final ExtractionJob job : latestJobs(packageId)) {
            all.addAll(resultRepository.findDetailedByJobId(job.getId()));
        }
        return all;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
