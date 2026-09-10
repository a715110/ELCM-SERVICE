package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.elcm.repository.pipeline.StagedDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregate counts for the four top metric cards (Uploading/Validating/
 * Valid/Submitted).
 *
 * REVISED 2026-08-19: real COUNT queries against staged_document, grouped
 * by lkp_staged_document_status.code, replacing the hardcoded mock counts.
 * All four numbers deliberately come from the SAME table (staged_document)
 * rather than mixing in the separate submission table for "Submitted" --
 * the four cards are shown together as one visual funnel, and
 * lkp_staged_document_status already has codes for exactly these four
 * stages (UPLOADING, VALIDATING, VALID, SUBMITTED), so this keeps all four
 * internally consistent rather than answering "Submitted" from a different
 * concept (e.g. a count of submission rows).
 */
@Service
@RequiredArgsConstructor
public class PipelineMetricsService {

    private final StagedDocumentRepository stagedDocumentRepository;

    @Transactional(readOnly = true)
    public PipelineMetrics getMetrics() {
        return new PipelineMetrics(
            (int) stagedDocumentRepository.countByStatus_Code("UPLOADING"),
            (int) stagedDocumentRepository.countByStatus_Code("VALIDATING"),
            (int) stagedDocumentRepository.countByStatus_Code("VALID"),
            (int) stagedDocumentRepository.countByStatus_Code("SUBMITTED")
        );
    }

    public record PipelineMetrics(int uploading, int validating, int valid, int submitted) {}
}
