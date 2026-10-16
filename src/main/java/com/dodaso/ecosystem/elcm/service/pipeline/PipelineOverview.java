package com.dodaso.ecosystem.elcm.service.pipeline;

/**
 * The numbers behind the Document Pipeline cards. Flat on purpose: the page reads the JSON as a
 * plain map. Documents are counted in the first card, packages in the others.
 *
 * <p>REVISED 2026-10-16: with "Mine only" the card numbers cover the caller's own work (documents
 * uploaded by the caller, packages assigned to the caller). failedExtractions and alertSentBack
 * always count everyone, so the red badge never hides a problem.
 *
 * @param stagedDocuments documents that are in no package
 * @param uploadedToday of those, uploaded since midnight UTC
 * @param inAssembly packages in ASSEMBLY, including ones sent back by a reviewer
 * @param sentBack packages in ASSEMBLY whose newest submission was declined
 * @param extracting packages whose newest submission is waiting or being read
 * @param queued of those, waiting to start
 * @param reading of those, being read now
 * @param readyForReview packages whose newest submission is extracted
 * @param fieldsNeedReview fields flagged for review across those packages
 * @param awaitingApproval packages whose newest submission is reviewed
 * @param oldestWaitingDays age in whole days of the longest waiting one, 0 when none
 * @param failedExtractions packages whose newest submission failed (everyone's)
 * @param alertSentBack packages sent back to Assembly (everyone's), for the red badge
 */
public record PipelineOverview(
    long stagedDocuments,
    long uploadedToday,
    long inAssembly,
    long sentBack,
    long extracting,
    long queued,
    long reading,
    long readyForReview,
    long fieldsNeedReview,
    long awaitingApproval,
    long oldestWaitingDays,
    long failedExtractions,
    long alertSentBack) {}
