package com.dodaso.ecosystem.elcm.purge;

import com.dodaso.ecosystem.elcm.repository.approval.ReviewNotationRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.DocumentClassificationRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionFieldResultRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionFieldRevisionRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionJobRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionReprocessRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.DeletedStagedDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADDED 2026-10-21. Removes the database rows of one soft-deleted staged document, all in one
 * transaction: the history that hangs off it (field revisions, field results, extraction jobs,
 * classifications, reprocess records, review notations) and then the document row itself. It is a
 * separate bean so the transaction applies when {@link StagedDocumentPurgeJob} calls it.
 *
 * <p>A document that still belongs to a package is never removed. The delete rules already stop
 * that from happening; this is a last guard.
 *
 * <p>The audit events of the document are kept on purpose: they are the record that it existed and
 * who deleted it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StagedDocumentPurger {

  private final DeletedStagedDocumentRepository deletedStagedDocumentRepository;
  private final PackageDocumentRepository packageDocumentRepository;
  private final ExtractionFieldRevisionRepository fieldRevisionRepository;
  private final ExtractionFieldResultRepository fieldResultRepository;
  private final ExtractionJobRepository jobRepository;
  private final DocumentClassificationRepository classificationRepository;
  private final ExtractionReprocessRepository reprocessRepository;
  private final ReviewNotationRepository reviewNotationRepository;

  /**
   * Removes the rows.
   *
   * @return true when the document row was removed, false when it was left alone
   */
  @Transactional
  public boolean purge(final Long documentId) {
    if (packageDocumentRepository.existsByStagedDocument_Id(documentId)) {
      log.warn("PURGE staged_document {} left alone: it still belongs to a package", documentId);
      return false;
    }
    fieldRevisionRepository.purgeByStagedDocumentId(documentId);
    fieldResultRepository.purgeByStagedDocumentId(documentId);
    jobRepository.purgeByStagedDocumentId(documentId);
    classificationRepository.purgeByStagedDocumentId(documentId);
    reprocessRepository.purgeByStagedDocumentId(documentId);
    reviewNotationRepository.purgeByStagedDocumentId(documentId);
    return deletedStagedDocumentRepository.purgeById(documentId) == 1;
  }
}
