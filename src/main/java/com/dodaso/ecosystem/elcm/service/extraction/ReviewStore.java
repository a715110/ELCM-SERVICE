package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionFieldResult;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionFieldRevision;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionJob;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionReprocess;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import com.dodaso.ecosystem.elcm.entity.pipeline.PackageDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.entity.pipeline.SubmissionSendBack;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionFieldResultRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionFieldRevisionRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionJobRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionReprocessRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpExtractionJobStatusRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpPackageStatusRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpStagedDocumentStatusRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpSubmissionStatusRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractPackageRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.StagedDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionSendBackRepository;
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
 * <p>
 * Rules (status codes the REST layer returns): 404 unknown package or field result, or a field that
 * does not belong to the package. 409 the package's newest submission is not EXTRACTED (still
 * running, failed, or already reviewed). 400 a corrected value that does not fit the field's data
 * type (ReviewRules). 422 Complete while fields still need confirming: every critical field, and
 * every field the extractor flagged for review, must be confirmed (verified_flag) first. This is
 * the rule in the schema comment on extraction_field_result (SDS 4.3). Decline (ADDED 2026-10-12)
 * sends the package back to Assembly with a reason; see decline(). Every change is also appended to
 * extraction_field_revision (who, when, before, after), so a field corrected several times keeps
 * its whole history; extraction_field_result keeps only the current state. Saving without a change
 * records nothing. Completing sets the submission to REVIEWED and nothing else: writing the
 * confirmed values to the contract record is a separate step that is not built yet.
 */
@Service
@RequiredArgsConstructor
public class ReviewStore {

  public static final String SUBMISSION_REVIEWED = "REVIEWED";
  public static final String SUBMISSION_DECLINED = "DECLINED";
  public static final int MAX_REASON_LENGTH = 1000;

  /** A changed value or a re-process needs a reason of at least this many characters. */
  public static final int MIN_CHANGE_REASON = 10;

  public static final int MAX_CHANGE_REASON = 500;

  private static final String ACTION_CORRECTED = "CORRECTED";
  private static final String ACTION_CONFIRMED = "CONFIRMED";
  private static final String ACTION_UNCONFIRMED = "UNCONFIRMED";
  private static final String ACTION_CONFIRM_ALL = "CONFIRM_ALL";

  private static final String PACKAGE_VALIDATED = "VALIDATED";
  private static final String PACKAGE_ASSEMBLY = "ASSEMBLY";
  /**
   * The status every document starts in; Unsubmit and Decline put documents back to it.
   */
  private static final String STAGED_DEFAULT = "UPLOADING";

  private final SubmissionRepository submissionRepository;
  private final ExtractionReprocessRepository reprocessRepository;
  private final SubmissionSendBackRepository sendBackRepository;
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
  private final ReviewerSeparation reviewerSeparation;

  /**
   * Saves one field: the corrected value (blank clears it) and whether the reviewer confirms it.
   * Confirming records who and when; un-confirming clears both.
   */
  @Transactional
  public void saveField(final Long packageId, final Long resultId, final String correctedValue,
      final boolean verified, final String reason, final String caller) {
    final Submission submission = reviewableSubmission(packageId);
    reviewerSeparation.check(submission, caller);
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
    // CHANGED 2026-10-14: changing a value needs a reason, kept with the revision.
    final String changeReason = valueChanged ? requireChangeReason(reason, "change a value") : null;
    final boolean wasVerified = Boolean.TRUE.equals(result.getVerifiedFlag());
    result.setCorrectedValue(value);
    applyVerified(result, verified, caller);
    resultRepository.save(result);
    if (valueChanged || wasVerified != verified) {
      final String action =
          valueChanged ? ACTION_CORRECTED : (verified ? ACTION_CONFIRMED : ACTION_UNCONFIRMED);
      record(result, beforeEffective, action, caller, changeReason);
    }
    submissionRepository.touch(submission.getId(), now());
  }

  /**
   * Confirms every field the extractor itself verified (status verified or inferred_absent) that
   * the reviewer has not confirmed yet and that is not flagged for review. Returns how many.
   */
  @Transactional
  public int confirmVerified(final Long packageId, final String caller) {
    reviewerSeparation.check(reviewableSubmission(packageId), caller);
    int changed = 0;
    for (final ExtractionFieldResult r : allResults(packageId)) {
      final String status = r.getFieldStatus();
      final boolean machineSure = "verified".equals(status) || "inferred_absent".equals(status);
      if (machineSure && !Boolean.TRUE.equals(r.getNeedsReviewFlag())
          && !Boolean.TRUE.equals(r.getVerifiedFlag())) {
        applyVerified(r, true, caller);
        resultRepository.save(r);
        record(r, effective(r), ACTION_CONFIRM_ALL, caller, null);
        changed++;
      }
    }
    return changed;
  }

  /**
   * Completes the review: 422 while any critical or flagged field is unconfirmed; 400 while any
   * document has no role yet (roles are optional at submit, ADDED 2026-10-14).
   */
  @Transactional
  public void complete(final Long packageId, final String caller) {
    final Submission submission = reviewableSubmission(packageId);
    reviewerSeparation.check(submission, caller);
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
          open
              + " field(s) still need to be confirmed (critical fields and fields marked Review).");
    }
    // ADDED 2026-10-14: roles are optional at submit, so they must be set by the end of review.
    final long undefinedRoles =
        packageDocumentRepository.countByContractPackage_Id(packageId)
            - packageDocumentRepository.countByContractPackage_IdAndDocumentRole_CodeNot(
                packageId, "UNDEFINED");
    if (undefinedRoles > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          undefinedRoles + " document(s) still need a role.");
    }
    final int moved = submissionRepository.complete(submission.getId(), submission.getStatus(),
        lkpSubmissionStatusRepository.findByCode(SUBMISSION_REVIEWED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Missing lkp_submission_status row: REVIEWED (run review_status.sql)")),
        caller, now());
    if (moved != 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "The review was already completed");
    }
  }

  /**
   * Reopens a completed review (ADDED 2026-10-12): the newest submission goes from REVIEWED back to
   * EXTRACTED, so the reviewer can change values again, complete the review again or decline.
   * Values, confirmations and history are kept. Who and when are recorded on the submission. 404 no
   * submission; 409 the submission is not REVIEWED (not completed yet, or already applied or
   * declined).
   */
  @Transactional
  public void reopen(final Long packageId, final String caller) {
    reopenNewest(packageId, caller);
  }

  /**
   * Approver sends a completed review back to the reviewer (ADDED 2026-10-14), with a reason. Same
   * move as reopen (REVIEWED back to EXTRACTED), plus one row in submission_send_back, so the
   * reviewer sees why and a package sent back several times keeps every reason. 404 no submission;
   * 409 the submission is not REVIEWED; 400 the reason is blank or over 1000 characters (checked
   * after the status, so a package that moved on answers 409 whatever was typed).
   */
  @Transactional
  public void sendBack(final Long packageId, final String reason, final String caller) {
    final Submission submission = newestSubmission(packageId);
    if (!SUBMISSION_REVIEWED.equals(submission.getStatus().getCode())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Only a package whose review is completed and not yet applied can be sent back");
    }
    final String text = ReviewRules.normalize(reason);
    if (text == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required");
    }
    if (text.length() > MAX_REASON_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The reason is longer than " + MAX_REASON_LENGTH + " characters");
    }
    final LocalDateTime now = now();
    reopenSubmission(submission, caller, now);
    final SubmissionSendBack row = new SubmissionSendBack();
    row.setSubmission(submission);
    row.setReason(text);
    row.setSentBy(caller != null && !caller.isBlank() ? caller : "system");
    row.setSentAt(now);
    sendBackRepository.save(row);
  }

  /** The send-backs of the package's newest submission, newest first; empty when none. */
  @Transactional(readOnly = true)
  public List<SendBackEntry> sendBacks(final Long packageId) {
    final Submission submission = newestSubmission(packageId);
    final List<SendBackEntry> entries = new ArrayList<>();
    for (final SubmissionSendBack r :
        sendBackRepository.findBySubmission_IdOrderByIdDesc(submission.getId())) {
      entries.add(new SendBackEntry(r.getId(), r.getReason(), r.getSentBy(),
          r.getSentAt() == null ? null : r.getSentAt().toString()));
    }
    return entries;
  }

  /**
   * The correction note of the newest submission together with the decline it answers (ADDED
   * 2026-10-14). resubmitted is false when the package has only one submission. 404 no submission.
   */
  @Transactional(readOnly = true)
  public Resubmission resubmission(final Long packageId) {
    final List<Submission> all = new ArrayList<>(submissionRepository.findByContractPackage_Id(packageId));
    if (all.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The package has no submission");
    }
    all.sort(Comparator.comparing(Submission::getId).reversed());
    final Submission newest = all.get(0);
    if (all.size() < 2) {
      return new Resubmission(false, null, null, null, null, null, null);
    }
    final Submission previous = all.get(1);
    return new Resubmission(
        true,
        newest.getCorrectionNote(),
        newest.getSubmittedBy(),
        newest.getSubmittedAt() == null ? null : newest.getSubmittedAt().toString(),
        previous.getDeclineReason(),
        previous.getDeclinedBy(),
        previous.getDeclinedAt() == null ? null : previous.getDeclinedAt().toString());
  }

  private Submission newestSubmission(final Long packageId) {
    return submissionRepository.findByContractPackage_Id(packageId).stream()
        .max(Comparator.comparing(Submission::getId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "The package has no submission"));
  }

  private void reopenNewest(final Long packageId, final String caller) {
    final Submission submission = newestSubmission(packageId);
    if (!SUBMISSION_REVIEWED.equals(submission.getStatus().getCode())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Only a package whose review is completed and not yet applied can be reopened");
    }
    reopenSubmission(submission, caller, now());
  }

  private void reopenSubmission(final Submission submission, final String caller,
      final LocalDateTime now) {
    final int moved = submissionRepository.reopen(submission.getId(), submission.getStatus(),
        lkpSubmissionStatusRepository.findByCode(ExtractionStore.SUBMISSION_EXTRACTED)
            .orElseThrow(
                () -> seedMissing("lkp_submission_status", ExtractionStore.SUBMISSION_EXTRACTED)),
        caller, now);
    if (moved != 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The review was changed by someone else");
    }
  }

  /**
   * Declines the package back to Assembly (ADDED 2026-10-12). Allowed while the newest submission
   * is EXTRACTED or FAILED. The reason is required (400 when blank or over 1000 characters). Then,
   * in one transaction (the submission is claimed first with a conditional update, so a concurrent
   * Retry cannot be overwritten): the submission becomes DECLINED with reason, who and when (kept
   * as history); the package returns VALIDATED to ASSEMBLY; its documents return SUBMITTED to
   * UPLOADING; and the completed extraction jobs become SUPERSEDED, so submitting again extracts
   * every document afresh instead of reusing results the reviewer rejected. 404 no submission, 409
   * the submission is not EXTRACTED or FAILED, or the package is not VALIDATED.
   */
  @Transactional
  public void decline(final Long packageId, final String reason, final String caller) {
    final Submission submission = submissionRepository.findByContractPackage_Id(packageId).stream()
        .max(Comparator.comparing(Submission::getId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "The package has no submission"));
    final String code = submission.getStatus().getCode();
    if (!ExtractionStore.SUBMISSION_EXTRACTED.equals(code)
        && !ExtractionStore.SUBMISSION_FAILED.equals(code)) {
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
    // Claim the transition first, in one conditional update: if a Retry (or anything else) moved
    // the submission since it was read, nothing below runs and the caller gets 409.
    final int claimed = submissionRepository.decline(submission.getId(), submission.getStatus(),
        lkpSubmissionStatusRepository.findByCode(SUBMISSION_DECLINED)
            .orElseThrow(() -> seedMissing("lkp_submission_status", SUBMISSION_DECLINED)),
        text, caller, now());
    if (claimed != 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The extraction changed while you were declining it. Reload and try again.");
    }
    // Jobs next: latestJobs() reads the completed jobs of the package's documents.
    final var superseded = lkpJobStatusRepository.findByCode(ExtractionStore.JOB_SUPERSEDED)
        .orElseThrow(
            () -> seedMissing("lkp_extraction_job_status", ExtractionStore.JOB_SUPERSEDED));
    for (final ExtractionJob job : latestJobs(packageId)) {
      job.setStatus(superseded);
      jobRepository.save(job);
    }
    final var start = lkpStagedDocumentStatusRepository.findByCode(STAGED_DEFAULT)
        .orElseThrow(() -> seedMissing("lkp_staged_document_status", STAGED_DEFAULT));
    for (final PackageDocument link : packageDocumentRepository.findDetailedByPackageId(
        packageId)) {
      link.getStagedDocument().setStatus(start);
      stagedDocumentRepository.save(link.getStagedDocument());
    }
    pkg.setStatus(lkpPackageStatusRepository.findByCode(PACKAGE_ASSEMBLY)
        .orElseThrow(() -> seedMissing("lkp_package_status", PACKAGE_ASSEMBLY)));
    contractPackageRepository.save(pkg);
  }

  private static ResponseStatusException seedMissing(final String table, final String code) {
    return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
        "Missing " + table + " row: " + code + " (run decline_status.sql)");
  }

  /**
   * The history of one field (any caller who can view the package). 404 when the field is not part
   * of the package's current extraction.
   */
  @Transactional(readOnly = true)
  public FieldHistory history(final Long packageId, final Long resultId) {
    final ExtractionFieldResult result = resultRepository.findById(resultId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field not found"));
    if (!latestJobIds(packageId).contains(result.getJob().getId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Field is not part of this package");
    }
    final List<FieldHistory.Revision> rows = new ArrayList<>();
    for (final ExtractionFieldRevision r : revisionRepository.findByFieldResult_IdOrderByRevisionNoAsc(
        resultId)) {
      rows.add(new FieldHistory.Revision(r.getRevisionNo(), r.getAction(), r.getPreviousValue(),
          r.getNewValue(), Boolean.TRUE.equals(r.getConfirmedFlag()), r.getChangedBy(),
          r.getChangedAt() == null ? null : r.getChangedAt().toString(),
          r.getReason()));
    }
    return new FieldHistory(resultId, result.getTemplateField().getLabel(),
        result.getExtractedValue(), rows);
  }

  /**
   * The value that counts: the corrected one when there is one, otherwise what the extractor
   * found.
   */
  static String effective(final ExtractionFieldResult r) {
    return r.getCorrectedValue() != null ? r.getCorrectedValue() : r.getExtractedValue();
  }

  /**
   * Appends one revision. previous is the effective value before the change; the new one is read
   * from the result.
   */
  private void record(final ExtractionFieldResult r, final String previous, final String action,
      final String caller, final String reason) {
    final ExtractionFieldRevision rev = new ExtractionFieldRevision();
    rev.setFieldResult(r);
    rev.setRevisionNo(revisionRepository.maxRevisionNo(r.getId()) + 1);
    rev.setPreviousValue(previous);
    rev.setNewValue(effective(r));
    rev.setConfirmedFlag(Boolean.TRUE.equals(r.getVerifiedFlag()));
    rev.setAction(action);
    rev.setChangedBy(caller);
    rev.setChangedAt(now());
    rev.setReason(reason);
    revisionRepository.save(rev);
  }

  /** The trimmed reason; 400 when it is missing, too short or too long. */
  private static String requireChangeReason(final String reason, final String what) {
    final String text = ReviewRules.normalize(reason);
    if (text == null || text.length() < MIN_CHANGE_REASON) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "A reason of at least " + MIN_CHANGE_REASON + " characters is needed to " + what + ".");
    }
    if (text.length() > MAX_CHANGE_REASON) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The reason is longer than " + MAX_CHANGE_REASON + " characters.");
    }
    return text;
  }

  /**
   * Has one document read again (ADDED 2026-10-14). While the review is open (the submission is
   * EXTRACTED), the document's current extraction job is set aside (SUPERSEDED, its results stay as
   * history), the reason is recorded, and the submission goes back to PENDING so the worker reads
   * only that document again (the other documents keep their extractions, corrections and
   * confirmations). Corrections and confirmations on the re-read document are lost: the new result
   * starts unconfirmed. 400 reason missing, under 10 or over 500 characters; 403 the caller
   * submitted the package; 404 no submission or the document is not in the package; 409 the review
   * is not open, the document has no finished extraction, or someone changed the submission first.
   */
  @Transactional
  public void reprocess(final Long packageId, final Long stagedDocumentId, final String reason,
      final String caller) {
    final Submission submission = reviewableSubmission(packageId);
    reviewerSeparation.check(submission, caller);
    final String text = requireChangeReason(reason, "have a document read again");
    boolean inPackage = false;
    for (final PackageDocument link : packageDocumentRepository.findDetailedByPackageId(packageId)) {
      if (link.getStagedDocument().getId().equals(stagedDocumentId)) {
        inPackage = true;
        break;
      }
    }
    if (!inPackage) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document is not in this package");
    }
    ExtractionJob current = null;
    for (final ExtractionJob job : latestJobs(packageId)) {
      if (job.getStagedDocument().getId().equals(stagedDocumentId)) {
        current = job;
      }
    }
    if (current == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The document has no finished extraction to read again");
    }
    final int moved = submissionRepository.moveStatus(submission.getId(), submission.getStatus(),
        lkpSubmissionStatusRepository.findByCode(ExtractionStore.SUBMISSION_PENDING)
            .orElseThrow(
                () -> seedMissing("lkp_submission_status", ExtractionStore.SUBMISSION_PENDING)),
        now());
    if (moved != 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The review was changed by someone else");
    }
    current.setStatus(lkpJobStatusRepository.findByCode(ExtractionStore.JOB_SUPERSEDED)
        .orElseThrow(
            () -> seedMissing("lkp_extraction_job_status", ExtractionStore.JOB_SUPERSEDED)));
    jobRepository.save(current);
    final ExtractionReprocess row = new ExtractionReprocess();
    row.setSubmission(submission);
    row.setStagedDocument(current.getStagedDocument());
    row.setPreviousJobId(current.getId());
    row.setReason(text);
    row.setRequestedBy(caller != null && !caller.isBlank() ? caller : "system");
    row.setRequestedAt(now());
    reprocessRepository.save(row);
  }

  private static void applyVerified(final ExtractionFieldResult r, final boolean verified,
      final String caller) {
    r.setVerifiedFlag(verified);
    r.setVerifiedBy(verified ? caller : null);
    r.setVerifiedAt(verified ? now() : null);
  }

  private Submission reviewableSubmission(final Long packageId) {
    final Optional<Submission> newest = submissionRepository.findByContractPackage_Id(packageId)
        .stream()
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

  /**
   * The newest COMPLETED job per document of the package.
   */
  private List<ExtractionJob> latestJobs(final Long packageId) {
    final Set<Long> docIds = new HashSet<>();
    for (final PackageDocument link : packageDocumentRepository.findDetailedByPackageId(
        packageId)) {
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

  /**
   * Package private: ApplyStore reads the same results the reviewer saw.
   */
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