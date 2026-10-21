package com.dodaso.ecosystem.elcm.purge;

import com.dodaso.ecosystem.elcm.entity.pipeline.DeletedStagedDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.Workspace;
import com.dodaso.ecosystem.elcm.repository.pipeline.DeletedStagedDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.WorkspaceRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADDED 2026-10-21. Nightly purge of soft-deleted staged documents that have passed their
 * retention period. The schedule is {@code elcm.purge.cron} (default 02:30 UTC). With {@code
 * elcm.purge.enabled=false}, the default, it does nothing.
 *
 * <p>For each workspace, in turn: the retention of that workspace is looked up, the deleted
 * documents older than that are read, and each one is purged on its own. A document is purged in
 * two steps. First common-service removes the file (blob, thumbnail, preview, file record). Only
 * when it confirms, the database rows are removed. If either step fails the document is left as it
 * is and tried again on the next run, and the other documents carry on.
 *
 * <p>Several instances of this service may run the job at the same time. That is safe, since every
 * step can be repeated, but it can log a few failures for rows another instance already removed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StagedDocumentPurgeJob {

  private final ElcmPurgeProperties properties;
  private final WorkspaceRepository workspaceRepository;
  private final DeletedStagedDocumentRepository deletedRepository;
  private final CommonFilePurgeClient commonFilePurgeClient;
  private final StagedDocumentPurger purger;

  @Scheduled(cron = "${elcm.purge.cron:0 30 2 * * *}", zone = "UTC")
  public void run() {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      runOnce();
    } catch (final RuntimeException e) {
      log.error("PURGE run failed: {}", e.getMessage(), e);
    }
  }

  /** One full pass over all workspaces. Public so a test or an admin trigger can call it. */
  public void runOnce() {
    final boolean dryRun = properties.isDryRun();
    int purged = 0;
    int failed = 0;
    int wouldPurge = 0;
    for (final Workspace workspace : workspaceRepository.findAll()) {
      final int days = properties.retentionFor(workspace.getCode());
      if (days <= 0) {
        log.info("PURGE workspace {} is kept forever (retention {} days)", workspace.getCode(), days);
        continue;
      }
      final LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
      final List<DeletedStagedDocument> due =
          deletedRepository.findByWorkspace_CodeAndDeletedAtBeforeOrderByIdAsc(
              workspace.getCode(), cutoff, PageRequest.of(0, Math.max(1, properties.getBatchSize())));
      for (final DeletedStagedDocument document : due) {
        if (dryRun) {
          wouldPurge++;
          log.info("PURGE dry run: would remove staged_document {} of {}, deleted {} by {}",
              document.getId(), workspace.getCode(), document.getDeletedAt(),
              document.getDeletedBy());
          continue;
        }
        if (purgeOne(document)) {
          purged++;
        } else {
          failed++;
        }
      }
    }
    if (dryRun) {
      log.info("PURGE dry run finished: {} staged document(s) would be removed", wouldPurge);
    } else {
      log.info("PURGE finished: {} staged document(s) removed, {} left for the next run", purged,
          failed);
    }
  }

  private boolean purgeOne(final DeletedStagedDocument document) {
    try {
      if (!commonFilePurgeClient.purge(document.getFileUploadId())) {
        return false;
      }
      return purger.purge(document.getId());
    } catch (final RuntimeException e) {
      log.error("PURGE staged_document {} failed, will be retried: {}", document.getId(),
          e.getMessage());
      return false;
    }
  }
}
