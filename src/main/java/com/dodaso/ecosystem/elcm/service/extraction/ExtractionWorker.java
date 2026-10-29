package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.elcm.integration.ai.ElcmAiIndexer;
import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import com.dodaso.ecosystem.elcm.activity.ActivityEvent;
import com.dodaso.ecosystem.elcm.activity.ActivityRecorder;
import com.dodaso.ecosystem.elcm.service.pipeline.FileUploadLookupService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The background side of "Submit for extraction". Every poll interval it looks for the oldest
 * PENDING submission, takes it, and extracts its documents one at a time (one document at a time
 * on purpose: the model uses every CPU core, so running two at once only makes both slower).
 * Nobody waits for this: the user submits, the screen shows progress by polling the status
 * endpoint, and the reviewer opens the package when it reads EXTRACTED.
 *
 * Failure handling: a document is tried maxAttempts times; if it still fails the job is FAILED
 * with the reason and the submission ends FAILED (the documents that did work keep their
 * results, and Retry only redoes the failed ones). A worker that dies mid-way is recovered by
 * the heartbeat check in ExtractionStore.recoverStale.
 */
@Component
public class ExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);

    private final ExtractionProperties properties;
    private final ExtractionStore store;
    private final ExtractorClient extractorClient;
    private final FileUploadLookupService fileUploadLookupService;
    private final RESTServiceClient restServiceClient;
    /** ADDED 2026-10-18: Pause is read from here. */
    private final ExtractionControlStore controlStore;
    /** ADDED 2026-10-20: the package Activity Log. */
    private final ActivityRecorder activityRecorder;
    /** ADDED 2026-10-28: makes an extracted document findable by AI search. */
    private final ElcmAiIndexer aiIndexer;

    private final AtomicBoolean busy = new AtomicBoolean(false);

    /**
     * ADDED 2026-10-15 -- set by wake() when work was just queued. poll() clears it before each
     * look at the queue and looks again if it was set meanwhile, so a wake that arrives while a poll
     * is finishing is not lost.
     */
    private final AtomicBoolean wakeRequested = new AtomicBoolean(false);

    /** ADDED 2026-10-15 -- runs wake() polls off the caller's thread (one at a time). */
    private final ExecutorService wakeExecutor = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "extraction-wake");
        thread.setDaemon(true);
        return thread;
    });

    /** ADDED 2026-10-15 -- when the stale check last ran (0 = not yet, so it runs on the first poll). */
    private volatile long lastRecoverAtMs;

    public ExtractionWorker(final ExtractionProperties properties, final ExtractionStore store,
                            final ExtractorClient extractorClient,
                            final FileUploadLookupService fileUploadLookupService,
                            final RESTServiceClient restServiceClient,
                            final ExtractionControlStore controlStore,
                            final ActivityRecorder activityRecorder,
                            final ElcmAiIndexer aiIndexer) {
        this.properties = properties;
        this.store = store;
        this.extractorClient = extractorClient;
        this.fileUploadLookupService = fileUploadLookupService;
        this.restServiceClient = restServiceClient;
        this.controlStore = controlStore;
        this.activityRecorder = activityRecorder;
        this.aiIndexer = aiIndexer;
    }

    /**
     * ADDED 2026-10-15 -- call after a submission was committed as PENDING (submit, retry, read
     * again). Starts a poll now instead of waiting for the next scheduled one, so the scheduled poll
     * can be slow. Does nothing when extraction is switched off. A poll already running picks the
     * new work up itself.
     */
    public void wake() {
        if (!properties.isEnabled()) {
            return;
        }
        wakeRequested.set(true);
        wakeExecutor.execute(this::poll);
    }

    @Scheduled(fixedDelayString = "${elcm.extraction.poll-interval-ms:60000}",
        initialDelayString = "${elcm.extraction.poll-interval-ms:60000}")
    public void poll() {
        if (!properties.isEnabled() || !busy.compareAndSet(false, true)) {
            return;
        }
        try {
            // ADDED 2026-10-15 -- the stale check runs every recoverIntervalMs, not on every poll.
            final long nowMs = System.currentTimeMillis();
            if (nowMs - lastRecoverAtMs >= properties.getRecoverIntervalMs()) {
                lastRecoverAtMs = nowMs;
                store.recoverStale(properties.getStaleMinutes());
            }
            // REVISED 2026-10-15 -- works through everything queued, then looks once more if a
            // wake() came in meanwhile. REVISED 2026-10-18: nothing new is taken while paused.
            do {
                wakeRequested.set(false);
                Long submissionId = controlStore.isPaused() ? null : store.claimNext();
                while (submissionId != null) {
                    process(submissionId);
                    submissionId = controlStore.isPaused() ? null : store.claimNext();
                }
            } while (wakeRequested.get());
        } catch (final Exception e) {
            // Never let an exception stop the schedule; the next poll starts clean.
            log.error("Extraction poll failed", e);
        } finally {
            busy.set(false);
        }
    }

    /** Public for tests. Runs one claimed submission to its end. */
    public void process(final Long submissionId) {
        boolean allOk = true;
        try {
            final ExtractionStore.Prepared prepared = store.prepareJobs(submissionId);
            // ADDED 2026-10-20 -- Activity Log
            record(submissionId, ActivityEvent.EXTRACTION_STARTED, "Extraction started on "
                + (prepared.tickets().size() + prepared.alreadyCompleted()) + " document(s).");
            if (prepared.tickets().isEmpty() && prepared.alreadyCompleted() == 0) {
                // No document has an active template with an extractor id: a configuration
                // problem, not a success. prepareJobs logged which documents were skipped.
                log.error("Extraction: submission {} has no document with an extraction template", submissionId);
                allOk = false;
            }
            for (final ExtractionStore.JobTicket ticket : prepared.tickets()) {
                // ADDED 2026-10-18 -- between two documents: was the package stopped, is the agent paused?
                if (!store.isProcessing(submissionId)) {
                    log.info("Extraction: submission {} was stopped, leaving it", submissionId);
                    return;
                }
                if (controlStore.isPaused()) {
                    if (store.releaseToPending(submissionId)) {
                        log.info("Extraction: paused, submission {} put back in the queue", submissionId);
                    }
                    return;
                }
                if (!runTicket(submissionId, ticket)) {
                    allOk = false;
                }
            }
        } catch (final Exception e) {
            log.error("Extraction: submission {} failed", submissionId, e);
            allOk = false;
        }
        if (!store.isProcessing(submissionId)) {
            log.info("Extraction: submission {} was stopped, leaving it", submissionId);
            return;
        }
        store.finish(submissionId, allOk);
        log.info("Extraction: submission {} finished, ok={}", submissionId, allOk);
        // ADDED 2026-10-20 -- Activity Log
        record(submissionId, allOk ? ActivityEvent.EXTRACTION_COMPLETED : ActivityEvent.EXTRACTION_FAILED,
            allOk ? "Extraction finished. The package is ready for review."
                : "Extraction failed for at least one document. Failed documents can be retried.");
    }

    private boolean runTicket(final Long submissionId, final ExtractionStore.JobTicket ticket) {
        String lastError = "unknown error";
        for (int attempt = 1; attempt <= Math.max(1, properties.getMaxAttempts()); attempt++) {
            store.markRunning(ticket.jobId(), submissionId);
            try {
                final Map<Long, FileUploadDTO> meta = fileUploadLookupService.findByIds(Set.of(ticket.fileUploadId()));
                final FileUploadDTO file = meta.get(ticket.fileUploadId());
                if (file == null) {
                    throw new ExtractorException("The file " + ticket.fileUploadId() + " was not found in file storage");
                }
                final byte[] content = download(ticket.fileUploadId());
                final ExtractorResult result = extractorClient.extract(ticket.extractorTemplateId(),
                    ticket.templateJson(), file.getFileName(), file.getContentType(), content,
                    () -> stopped(submissionId));
                store.saveResult(ticket.jobId(), submissionId, result);
                // ADDED 2026-10-28 -- AI search; runs in the background and never fails extraction
                aiIndexer.stagedDocumentExtracted(ticket.stagedDocumentId(), file.getFileName(), content);
                return true;
            } catch (final ExtractorCancelledException e) {
                // ADDED 2026-10-18 -- the package was stopped while the extractor was reading.
                store.requeueRunningJob(ticket.jobId());
                log.info("Extraction: job {} cancelled, submission {} was stopped", ticket.jobId(), submissionId);
                return false;
            } catch (final Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("Extraction: job {} attempt {} failed: {}", ticket.jobId(), attempt, lastError);
                if (attempt < properties.getMaxAttempts()) {
                    sleep(properties.getRetryDelayMs());
                }
            }
        }
        store.markFailed(ticket.jobId(), submissionId, lastError);
        return false;
    }

    /** ADDED 2026-10-20 -- one Activity Log line for the package of a submission. Never throws. */
    private void record(final Long submissionId, final ActivityEvent event, final String message) {
        try {
            activityRecorder.recordSystem(store.packageIdOf(submissionId), event, message);
        } catch (final RuntimeException e) {
            log.warn("ACTIVITY-LOG could not note {} for submission {}: {}", event, submissionId, e.getMessage());
        }
    }

    /** ADDED 2026-10-18 -- asked every few seconds during an extractor call; a failed check means no. */
    private boolean stopped(final Long submissionId) {
        try {
            return !store.isProcessing(submissionId);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    private byte[] download(final Long fileUploadId) throws Exception {
        final byte[] content = restServiceClient.callRESTService(
            ServiceDiscoveryEnum.common_service.getServiceDiscoveryName(),
            "/api/v1/files/" + fileUploadId + "/download",
            null,
            new ParameterizedTypeReference<byte[]>() {
            },
            HttpMethod.GET);
        if (content == null || content.length == 0) {
            throw new ExtractorException("The file " + fileUploadId + " is empty in file storage");
        }
        return content;
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
