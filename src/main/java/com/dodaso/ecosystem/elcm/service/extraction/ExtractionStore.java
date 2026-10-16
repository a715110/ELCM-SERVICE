package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionFieldResult;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionJob;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionTemplate;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionTemplateField;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpExtractionJobStatus;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpSubmissionStatus;
import com.dodaso.ecosystem.elcm.entity.pipeline.PackageDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.StagedDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionFieldResultRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionJobRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionTemplateFieldRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionTemplateRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpExtractionJobStatusRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpSubmissionStatusRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import com.dodaso.ecosystem.elcm.service.template.TemplateDefinitions;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The database side of extraction: every method is one short transaction. The worker calls these
 * between the slow steps (download, extract), so no database transaction or connection is held
 * open while the model is thinking. A separate bean from the worker on purpose, so the
 * transaction proxy applies (same reason as ContractPackageDataService).
 *
 * Submission statuses: PENDING (waiting) -> PROCESSING (a worker has it) -> EXTRACTED (ready for
 * review) or FAILED (a person must look; Retry puts it back to PENDING). A reviewer can end an
 * EXTRACTED or FAILED submission as DECLINED (see ReviewStore), which returns the package to Assembly.
 * Job statuses: QUEUED -> RUNNING -> COMPLETED or FAILED; COMPLETED becomes SUPERSEDED when the package is declined.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExtractionStore {

    public static final String SUBMISSION_PENDING = "PENDING";
    public static final String SUBMISSION_PROCESSING = "PROCESSING";
    public static final String SUBMISSION_EXTRACTED = "EXTRACTED";
    public static final String SUBMISSION_FAILED = "FAILED";
    public static final String JOB_QUEUED = "QUEUED";
    public static final String JOB_RUNNING = "RUNNING";
    public static final String JOB_COMPLETED = "COMPLETED";
    public static final String JOB_FAILED = "FAILED";
    /** A completed job whose package was declined; the next submission extracts the document again. */
    public static final String JOB_SUPERSEDED = "SUPERSEDED";

    private static final String WORKER = "extraction-worker";

    private final SubmissionRepository submissionRepository;
    private final LkpSubmissionStatusRepository lkpSubmissionStatusRepository;
    private final LkpExtractionJobStatusRepository lkpJobStatusRepository;
    private final PackageDocumentRepository packageDocumentRepository;
    private final ExtractionTemplateRepository templateRepository;
    private final ExtractionTemplateFieldRepository templateFieldRepository;
    private final ExtractionJobRepository jobRepository;
    private final ExtractionFieldResultRepository resultRepository;

    /**
     * One document to extract: what the worker needs, with no entity attached. REVISED 2026-10-15:
     * templateJson is the published template as stored in the database, sent to the extractor with
     * the document. It is null for a template that predates template management (no field
     * descriptions); the extractor then reads its own YAML file named extractorTemplateId.
     */
    public record JobTicket(Long jobId, Long stagedDocumentId, Long fileUploadId, String extractorTemplateId,
                            String templateJson) {
    }

    /** What prepareJobs found: jobs to run, and how many documents were already done. */
    public record Prepared(List<JobTicket> tickets, int alreadyCompleted) {
    }

    /**
     * Takes the oldest PENDING submission and marks it PROCESSING. Returns its id, or null when
     * the queue is empty or another worker won the race.
     */
    @Transactional
    public Long claimNext() {
        final Optional<Submission> next = submissionRepository.findFirstByStatus_CodeOrderByIdAsc(SUBMISSION_PENDING);
        if (next.isEmpty()) {
            return null;
        }
        final Submission s = next.get();
        final int won = submissionRepository.moveStatus(s.getId(), s.getStatus(),
            submissionStatus(SUBMISSION_PROCESSING), now());
        return won == 1 ? s.getId() : null;
    }

    /**
     * Puts PROCESSING submissions whose heartbeat stopped back to PENDING, and their RUNNING jobs
     * back to QUEUED. Returns how many submissions were recovered.
     */
    @Transactional
    public int recoverStale(final int staleMinutes) {
        final LocalDateTime limit = now().minus(Duration.ofMinutes(staleMinutes));
        int recovered = 0;
        // ADDED 2026-10-15 -- the database returns only the stale ones (usually none).
        for (final Submission s : submissionRepository
            .findByStatus_CodeAndUpdatedAtBeforeOrderByIdAsc(SUBMISSION_PROCESSING, limit)) {
            final Set<Long> docIds = documentIds(s.getContractPackage().getId());
            if (!docIds.isEmpty()) {
                jobRepository.requeueRunning(jobStatus(JOB_QUEUED), jobStatus(JOB_RUNNING), docIds);
            }
            recovered += submissionRepository.moveStatus(s.getId(), s.getStatus(),
                submissionStatus(SUBMISSION_PENDING), now());
            log.warn("Extraction: submission {} had no heartbeat for {} minutes, put back to PENDING",
                s.getId(), staleMinutes);
        }
        return recovered;
    }

    /**
     * Creates (or reuses) one job per document of the submission's package. A document whose
     * contract type has no active template gets no job (it is skipped, and logged). A document
     * whose newest job is already COMPLETED is not done again, which is what makes Retry cheap:
     * only the failed documents run. Returns the jobs still to run and the count already done.
     */
    @Transactional
    public Prepared prepareJobs(final Long submissionId) {
        final Submission submission = submissionRepository.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found"));
        final LkpExtractionJobStatus queued = jobStatus(JOB_QUEUED);
        final List<JobTicket> tickets = new ArrayList<>();
        int alreadyCompleted = 0;
        final Map<Long, String> definitions = new HashMap<>(); // template id -> JSON, built once per template
        for (final PackageDocument link : packageDocumentRepository
            .findDetailedByPackageId(submission.getContractPackage().getId())) {
            final StagedDocument doc = link.getStagedDocument();
            final Optional<ExtractionTemplate> template = templateRepository
                .findFirstByContractType_IdAndIsActiveTrueOrderByIdDesc(doc.getContractType().getId());
            if (template.isEmpty() || isBlank(template.get().getExtractorTemplateId())) {
                log.warn("Extraction: document {} (contract type {}) has no active template with an extractor id; skipped",
                    doc.getId(), doc.getContractType().getCode());
                continue;
            }
            final Optional<ExtractionJob> existing = jobRepository.findFirstByStagedDocument_IdOrderByIdDesc(doc.getId());
            if (existing.isPresent() && JOB_COMPLETED.equals(existing.get().getStatus().getCode())) {
                alreadyCompleted++;
                continue;
            }
            // A superseded job is history (the package was declined): keep it and extract afresh.
            final boolean reuse = existing.isPresent()
                && !JOB_SUPERSEDED.equals(existing.get().getStatus().getCode());
            final ExtractionJob job = reuse ? existing.get() : new ExtractionJob();
            job.setStagedDocument(doc);
            job.setTemplate(template.get());
            job.setStatus(queued);
            job.setErrorMessage(null);
            if (job.getAttemptCount() == null) {
                job.setAttemptCount(0);
            }
            final ExtractionJob saved = jobRepository.save(job);
            final Long templateId = template.get().getId();
            if (!definitions.containsKey(templateId)) {
                // ADDED 2026-10-15 -- the definition sent to the extractor comes from the database, so
                // publishing in the Extraction Templates screen is what changes extraction.
                definitions.put(templateId, TemplateDefinitions.forExtractor(template.get(),
                    templateFieldRepository.findByTemplate_IdOrderByDisplayOrder(templateId)));
            }
            tickets.add(new JobTicket(saved.getId(), doc.getId(), doc.getFileUploadId(),
                template.get().getExtractorTemplateId(), definitions.get(templateId)));
        }
        return new Prepared(tickets, alreadyCompleted);
    }

    @Transactional
    public void markRunning(final Long jobId, final Long submissionId) {
        final ExtractionJob job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus(jobStatus(JOB_RUNNING));
        job.setStartedAt(now());
        job.setCompletedAt(null);
        job.setAttemptCount((job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1);
        jobRepository.save(job);
        submissionRepository.touch(submissionId, now());
    }

    /** Stores the extractor's answer for one job and marks it COMPLETED. Replaces earlier results of the job. */
    @Transactional
    public void saveResult(final Long jobId, final Long submissionId, final ExtractorResult result) {
        final ExtractionJob job = jobRepository.findById(jobId).orElseThrow();
        final Map<String, ExtractionTemplateField> byKey = new HashMap<>();
        for (final ExtractionTemplateField f : templateFieldRepository.findByTemplate_IdOrderByDisplayOrder(
            job.getTemplate().getId())) {
            byKey.put(f.getFieldKey(), f);
        }
        resultRepository.deleteByJob_Id(jobId);
        resultRepository.flush();
        final Set<String> review = new HashSet<>(result.needsReviewOrEmpty());
        final Set<String> seen = new HashSet<>();
        int flagged = 0;
        for (final ExtractorResult.Field field : result.fieldsOrEmpty()) {
            final ExtractionTemplateField templateField = byKey.get(field.key());
            if (templateField == null) {
                log.warn("Extraction: job {} returned field '{}' that is not in template {}; ignored",
                    jobId, field.key(), job.getTemplate().getId());
                continue;
            }
            if (!seen.add(field.key())) {
                continue;
            }
            final ExtractionFieldResult row = new ExtractionFieldResult();
            row.setJob(job);
            row.setTemplateField(templateField);
            row.setExtractedValue(cut(valueText(field.value()), 2000));
            row.setFieldStatus(cut(field.status(), 30));
            row.setQuote(cut(field.quote(), 2000));
            row.setPageNo(field.page() != null && field.page() > 0 ? field.page() : null);
            row.setProblem(cut(field.problem(), 500));
            row.setNote(cut(field.note(), 500));
            row.setCandidates(field.candidates() == null || field.candidates().isEmpty()
                ? null : cut(String.join("\n", field.candidates()), 2000));
            final boolean needs = review.contains(field.key());
            row.setNeedsReviewFlag(needs);
            row.setVerifiedFlag(false);
            if (needs) {
                flagged++;
            }
            resultRepository.save(row);
        }
        final LocalDateTime done = now();
        job.setStatus(jobStatus(JOB_COMPLETED));
        job.setCompletedAt(done);
        job.setDurationSeconds(job.getStartedAt() == null ? null
            : (int) Math.max(0, Duration.between(job.getStartedAt(), done).getSeconds()));
        job.setModelName(cut(result.model(), 100));
        job.setAgent("extractor");
        job.setNeedsReviewCount(flagged);
        job.setErrorMessage(null);
        jobRepository.save(job);
        submissionRepository.touch(submissionId, now());
    }

    @Transactional
    public void markFailed(final Long jobId, final Long submissionId, final String message) {
        final ExtractionJob job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus(jobStatus(JOB_FAILED));
        job.setCompletedAt(now());
        job.setErrorMessage(cut(message, 1000));
        jobRepository.save(job);
        submissionRepository.touch(submissionId, now());
    }

    /** Ends the submission: EXTRACTED when nothing failed and at least one document ran, else FAILED. */
    @Transactional
    public void finish(final Long submissionId, final boolean allOk) {
        final Submission s = submissionRepository.findById(submissionId).orElseThrow();
        submissionRepository.moveStatus(submissionId, s.getStatus(),
            submissionStatus(allOk ? SUBMISSION_EXTRACTED : SUBMISSION_FAILED), now());
    }

    /** Retry: FAILED back to PENDING. 404 unknown package, 409 when the newest submission is not FAILED. */
    @Transactional
    public void requeueFailed(final Long packageId) {
        final List<Submission> all = submissionRepository.findByContractPackage_Id(packageId);
        if (all.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The package has no submission");
        }
        final Submission newest = all.stream().max(java.util.Comparator.comparing(Submission::getId)).orElseThrow();
        if (!SUBMISSION_FAILED.equals(newest.getStatus().getCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a failed extraction can be retried");
        }
        submissionRepository.moveStatus(newest.getId(), newest.getStatus(),
            submissionStatus(SUBMISSION_PENDING), now());
    }

    private Set<Long> documentIds(final Long packageId) {
        final Set<Long> ids = new HashSet<>();
        for (final PackageDocument link : packageDocumentRepository.findDetailedByPackageId(packageId)) {
            ids.add(link.getStagedDocument().getId());
        }
        return ids;
    }

    private LkpSubmissionStatus submissionStatus(final String code) {
        return lkpSubmissionStatusRepository.findByCode(code)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Missing lkp_submission_status row: " + code + " (run extraction_wiring.sql)"));
    }

    private LkpExtractionJobStatus jobStatus(final String code) {
        return lkpJobStatusRepository.findByCode(code)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Missing lkp_extraction_job_status row: " + code + " (run extraction_wiring.sql)"));
    }

    static String valueText(final JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        final String text = value.isTextual() ? value.asText() : value.toString();
        return text.isBlank() ? null : text;
    }

    private static String cut(final String text, final int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static boolean isBlank(final String s) {
        return s == null || s.isBlank();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
