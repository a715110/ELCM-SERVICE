package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.elcm.service.extraction.ApplyStore;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionStore;
import com.dodaso.ecosystem.elcm.service.extraction.ReviewStore;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The numbers for the Document Pipeline cards.
 *
 * <p>REVISED 2026-10-16: the four document-status cards (Uploading, Validating, Valid, Submitted)
 * are replaced by five cards that follow the real flow: Staged documents, In assembly, Extracting,
 * Ready for review and Awaiting approval. Applied packages are not a card (they live in Contract
 * Records). Failed extractions are reported as a count for the red badge in the page header.
 *
 * <p>Four queries at most: documents in no package (with the uploaded-today split), packages by
 * status, the newest submission of every package (same grouped pattern as loadPackages), and, only
 * when something is ready for review, the review flags of the newest extraction job of each
 * document. The newest submission decides the stage of a package.
 */
@Service
@RequiredArgsConstructor
public class PipelineMetricsService {

    private static final String ASSEMBLY = "ASSEMBLY";

    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public PipelineOverview getOverview() {
        final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        final LocalDateTime startOfDay = now.toLocalDate().atStartOfDay();

        // Card 1: documents in no package. Deleted ones are excluded by @SQLRestriction.
        final Object[] docs = entityManager
            .createQuery("select count(d), coalesce(sum(case when d.uploadedAt >= :start then 1 else 0 end), 0) "
                + "from StagedDocument d where not exists "
                + "(select 1 from PackageDocument pd where pd.stagedDocument = d)", Object[].class)
            .setParameter("start", startOfDay)
            .getSingleResult();

        // Card 2: packages still in assembly.
        final long inAssembly = entityManager
            .createQuery("select count(p) from ContractPackage p where p.status.code = :code", Long.class)
            .setParameter("code", ASSEMBLY)
            .getSingleResult();

        long sentBack = 0;
        long queued = 0;
        long reading = 0;
        long ready = 0;
        long awaiting = 0;
        long failed = 0;
        LocalDateTime oldestReviewed = null;
        final List<Long> readyPackageIds = new ArrayList<>();

        for (final Object[] row : entityManager
            .createQuery("select s.contractPackage.id, s.contractPackage.status.code, s.status.code, "
                + "s.reviewedAt, s.updatedAt from Submission s "
                + "where s.id in (select max(s2.id) from Submission s2 group by s2.contractPackage.id)",
                Object[].class)
            .getResultList()) {
            final String packageStatus = (String) row[1];
            final String submissionStatus = (String) row[2];
            switch (submissionStatus) {
                case ExtractionStore.SUBMISSION_PENDING -> queued++;
                case ExtractionStore.SUBMISSION_PROCESSING -> reading++;
                case ExtractionStore.SUBMISSION_EXTRACTED -> {
                    ready++;
                    readyPackageIds.add((Long) row[0]);
                }
                case ExtractionStore.SUBMISSION_FAILED -> failed++;
                case ReviewStore.SUBMISSION_DECLINED -> {
                    if (ASSEMBLY.equals(packageStatus)) {
                        sentBack++;
                    }
                }
                case ReviewStore.SUBMISSION_REVIEWED -> {
                    awaiting++;
                    final LocalDateTime since = row[3] != null ? (LocalDateTime) row[3] : (LocalDateTime) row[4];
                    if (since != null && (oldestReviewed == null || since.isBefore(oldestReviewed))) {
                        oldestReviewed = since;
                    }
                }
                case ApplyStore.SUBMISSION_APPLIED -> { }
                default -> { }
            }
        }

        long fieldsNeedReview = 0;
        if (!readyPackageIds.isEmpty()) {
            // Newest job of each document, completed only, for the documents of the ready packages.
            // A document sits in one package at a time, so nothing is counted twice.
            final Long sum = entityManager
                .createQuery("select coalesce(sum(j.needsReviewCount), 0) from ExtractionJob j "
                    + "where j.status.code = :completed "
                    + "and j.id in (select max(j2.id) from ExtractionJob j2 group by j2.stagedDocument.id) "
                    + "and j.stagedDocument.id in (select pd.stagedDocument.id from PackageDocument pd "
                    + "where pd.contractPackage.id in :ids)", Long.class)
                .setParameter("completed", ExtractionStore.JOB_COMPLETED)
                .setParameter("ids", readyPackageIds)
                .getSingleResult();
            fieldsNeedReview = sum == null ? 0 : sum;
        }

        final long oldestDays = oldestReviewed == null
            ? 0 : Math.max(0, Duration.between(oldestReviewed, now).toDays());

        return new PipelineOverview(
            ((Number) docs[0]).longValue(),
            ((Number) docs[1]).longValue(),
            inAssembly,
            sentBack,
            queued + reading,
            queued,
            reading,
            ready,
            fieldsNeedReview,
            awaiting,
            oldestDays,
            failed);
    }
}
