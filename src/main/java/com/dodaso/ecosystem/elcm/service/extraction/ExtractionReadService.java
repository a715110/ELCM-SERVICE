package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionFieldResult;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionJob;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionTemplateField;
import com.dodaso.ecosystem.elcm.entity.pipeline.PackageDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.StagedDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionFieldResultRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionJobRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionTemplateRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractPackageRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read side of extraction: progress and results of one package. Read-only transactions; file
 * names are added afterwards by ExtractionService (they come from common-service, over the wire).
 */
@Service
@RequiredArgsConstructor
public class ExtractionReadService {

    private final ContractPackageRepository contractPackageRepository;
    private final PackageDocumentRepository packageDocumentRepository;
    private final SubmissionRepository submissionRepository;
    private final ExtractionJobRepository jobRepository;
    private final ExtractionFieldResultRepository resultRepository;
    private final ExtractionTemplateRepository templateRepository;

    /** Job status shown for a document that is not read because it has no extraction template. */
    public static final String JOB_SKIPPED = "SKIPPED";
    static final String NO_TEMPLATE_MESSAGE = "No extraction template for this document's contract type.";

    /** Same rule as ExtractionStore.prepareJobs: an active template with an extractor id. */
    private boolean hasTemplate(final StagedDocument doc) {
        return templateRepository.findFirstByContractType_IdAndIsActiveTrueOrderByIdDesc(doc.getContractType().getId())
            .filter(t -> t.getExtractorTemplateId() != null && !t.getExtractorTemplateId().isBlank())
            .isPresent();
    }

    /** File upload ids of the package's documents that cannot be read for lack of a template. */
    @Transactional(readOnly = true)
    public List<Long> documentsWithoutTemplate(final Long packageId) {
        requirePackage(packageId);
        final List<Long> ids = new ArrayList<>();
        for (final PackageDocument link : packageDocumentRepository.findDetailedByPackageId(packageId)) {
            if (!hasTemplate(link.getStagedDocument())) {
                ids.add(link.getStagedDocument().getFileUploadId());
            }
        }
        return ids;
    }

    @Transactional(readOnly = true)
    public ExtractionStatus status(final Long packageId) {
        requirePackage(packageId);
        final Optional<Submission> newest = newestSubmission(packageId);
        final List<PackageDocument> links = packageDocumentRepository.findDetailedByPackageId(packageId);
        if (newest.isEmpty()) {
            return new ExtractionStatus(packageId, null, "NOT_SUBMITTED", "Not submitted for extraction yet.",
                null, links.size(), 0, 0, 0, List.of());
        }
        final Submission submission = newest.get();
        final String code = submission.getStatus().getCode();
        final Map<Long, ExtractionJob> jobs = latestJobs(links);
        final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        final List<ExtractionStatus.DocumentProgress> rows = new ArrayList<>();
        int done = 0;
        int failed = 0;
        int skipped = 0;
        int needsReview = 0;
        for (final PackageDocument link : links) {
            final Long docId = link.getStagedDocument().getId();
            final ExtractionJob job = jobs.get(docId);
            if (job == null && !hasTemplate(link.getStagedDocument())) {
                // Never gets a job: shown as skipped instead of queued for ever, and left out of the total.
                skipped++;
                rows.add(new ExtractionStatus.DocumentProgress(docId, link.getStagedDocument().getFileUploadId(), null,
                    JOB_SKIPPED, 0, null, null, null, NO_TEMPLATE_MESSAGE));
                continue;
            }
            final String jobStatus = job == null ? ExtractionStore.JOB_QUEUED : job.getStatus().getCode();
            Integer elapsed = null;
            if (job != null && ExtractionStore.JOB_RUNNING.equals(jobStatus) && job.getStartedAt() != null) {
                elapsed = (int) Math.max(0, Duration.between(job.getStartedAt(), now).getSeconds());
            } else if (job != null) {
                elapsed = job.getDurationSeconds();
            }
            if (ExtractionStore.JOB_COMPLETED.equals(jobStatus)) {
                done++;
                needsReview += job.getNeedsReviewCount() == null ? 0 : job.getNeedsReviewCount();
            } else if (ExtractionStore.JOB_FAILED.equals(jobStatus)) {
                failed++;
            }
            rows.add(new ExtractionStatus.DocumentProgress(docId, link.getStagedDocument().getFileUploadId(), null,
                jobStatus, job == null || job.getAttemptCount() == null ? 0 : job.getAttemptCount(),
                job == null ? null : job.getStartedAt(), elapsed,
                job == null ? null : job.getNeedsReviewCount(),
                job == null ? null : job.getErrorMessage()));
        }

        final String state;
        final String message;
        Integer position = null;
        switch (code) {
            case ExtractionStore.SUBMISSION_PENDING -> {
                state = "QUEUED";
                position = queuePosition(submission);
                message = position <= 1 ? "Waiting to start." : "Waiting in line (" + (position - 1) + " ahead).";
            }
            case ExtractionStore.SUBMISSION_PROCESSING -> {
                state = "RUNNING";
                final int readable = links.size() - skipped;
                message = "Reading document " + Math.min(readable, done + failed + 1) + " of " + readable + ".";
            }
            case ExtractionStore.SUBMISSION_EXTRACTED -> {
                state = "DONE";
                message = (needsReview == 0 ? "Done. Nothing needs review."
                    : "Done. " + needsReview + " field(s) need review.")
                    + (skipped == 0 ? "" : " " + skipped + " document(s) were not read: no extraction template.");
            }
            case ReviewStore.SUBMISSION_DECLINED -> {
                state = "DECLINED";
                message = "Declined by " + (submission.getDeclinedBy() == null ? "a reviewer" : submission.getDeclinedBy())
                    + ": " + (submission.getDeclineReason() == null ? "" : submission.getDeclineReason());
            }
            case ReviewStore.SUBMISSION_REVIEWED -> {
                state = "REVIEWED";
                message = "Review completed.";
            }
            case ApplyStore.SUBMISSION_APPLIED -> {
                state = "APPLIED";
                final var target = submission.getContractPackage().getTargetRecord();
                message = "Applied to contract record " + (target == null ? "" : target.getRecordCode()) + ".";
            }
            case ExtractionStore.SUBMISSION_FAILED -> {
                state = "FAILED";
                message = failed > 0 ? failed + " document(s) failed." : "No document has an extraction template.";
            }
            default -> {
                state = code;
                message = "";
            }
        }
        return new ExtractionStatus(packageId, submission.getId(), state, message, position,
            links.size() - skipped, done, failed, needsReview, rows);
    }

    @Transactional(readOnly = true)
    public ExtractionResults results(final Long packageId) {
        requirePackage(packageId);
        final List<PackageDocument> links = packageDocumentRepository.findDetailedByPackageId(packageId);
        final Map<Long, ExtractionJob> jobs = latestJobs(links);
        final List<ExtractionResults.DocumentResult> docs = new ArrayList<>();
        for (final PackageDocument link : links) {
            final Long docId = link.getStagedDocument().getId();
            final ExtractionJob job = jobs.get(docId);
            if (job == null) {
                continue;
            }
            final List<ExtractionResults.FieldRow> fields = new ArrayList<>();
            if (ExtractionStore.JOB_COMPLETED.equals(job.getStatus().getCode())) {
                for (final ExtractionFieldResult r : resultRepository.findDetailedByJobId(job.getId())) {
                    final ExtractionTemplateField f = r.getTemplateField();
                    fields.add(new ExtractionResults.FieldRow(r.getId(), f.getFieldKey(), f.getLabel(),
                        f.getDataType().getCode(), Boolean.TRUE.equals(f.getIsCritical()),
                        Boolean.TRUE.equals(f.getIsRequired()), r.getExtractedValue(), r.getCorrectedValue(),
                        r.getFieldStatus(), r.getQuote(), r.getProblem(), r.getNote(), r.getCandidates(),
                        Boolean.TRUE.equals(r.getNeedsReviewFlag()), Boolean.TRUE.equals(r.getVerifiedFlag()),
                        r.getVerifiedBy(), r.getPageNo()));
                }
            }
            docs.add(new ExtractionResults.DocumentResult(docId, link.getStagedDocument().getFileUploadId(), null,
                job.getStatus().getCode(), job.getModelName(),
                job.getNeedsReviewCount() == null ? 0 : job.getNeedsReviewCount(), fields));
        }
        return new ExtractionResults(packageId, docs);
    }

    private void requirePackage(final Long packageId) {
        if (!contractPackageRepository.existsById(packageId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found");
        }
    }

    private Optional<Submission> newestSubmission(final Long packageId) {
        return submissionRepository.findByContractPackage_Id(packageId).stream()
            .max(Comparator.comparing(Submission::getId));
    }

    /** 1 means next up. Counts PENDING submissions with a smaller or equal id. */
    private int queuePosition(final Submission submission) {
        int position = 0;
        for (final Submission s : submissionRepository.findByStatus_CodeOrderByIdAsc(ExtractionStore.SUBMISSION_PENDING)) {
            if (s.getId() <= submission.getId()) {
                position++;
            }
        }
        return Math.max(1, position);
    }

    private Map<Long, ExtractionJob> latestJobs(final List<PackageDocument> links) {
        final Set<Long> ids = links.stream().map(l -> l.getStagedDocument().getId()).collect(Collectors.toSet());
        final Map<Long, ExtractionJob> latest = new HashMap<>();
        if (ids.isEmpty()) {
            return latest;
        }
        for (final ExtractionJob job : jobRepository.findDetailedByStagedDocumentIds(ids)) {
            latest.put(job.getStagedDocument().getId(), job); // ordered by id, the last one wins
        }
        return latest;
    }
}
