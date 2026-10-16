package com.dodaso.ecosystem.elcm.service.pipeline;

import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dodaso.ecosystem.elcm.container.cust.PipelineMetricsDTOContainer;
import com.dodaso.ecosystem.elcm.dto.cust.PipelineMetricsDTO;

import lombok.RequiredArgsConstructor;

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
 *
 * REVISED 2026-10-15: one grouped query for all four counts instead of four count queries. Deleted
 * documents stay out because StagedDocument carries @SQLRestriction("deleted_at IS NULL").
 */
@Service
@RequiredArgsConstructor
public class PipelineMetricsService {

    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public PipelineMetricsDTOContainer getMetrics() {
        final Map<String, Long> counts = new HashMap<>();
        for (final Object[] row : entityManager
            .createQuery("select d.status.code, count(d) from StagedDocument d group by d.status.code",
                Object[].class)
            .getResultList()) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }

        final PipelineMetricsDTOContainer dtoContainer = new PipelineMetricsDTOContainer();
        final PipelineMetricsDTO instance = new PipelineMetricsDTO(
            counts.getOrDefault("UPLOADING", 0L).intValue(),
            counts.getOrDefault("VALIDATING", 0L).intValue(),
            counts.getOrDefault("VALID", 0L).intValue(),
            counts.getOrDefault("SUBMITTED", 0L).intValue()
        );

        dtoContainer.setPipelineMetricsDTO(instance);
        return dtoContainer;
    }

}
