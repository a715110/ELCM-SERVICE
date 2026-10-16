package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.elcm.service.extraction.ApplyStore;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionStore;
import com.dodaso.ecosystem.elcm.service.extraction.ReviewStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The numbers for the Document Pipeline cards.
 *
 * <p>REVISED 2026-10-16: the four document-status cards (Uploading, Validating, Valid, Submitted)
 * are replaced by five cards that follow the real flow: Staged documents, In assembly, Extracting,
 * Ready for review and Awaiting approval. Applied packages are not a card (they live in Contract
 * Records). Failed extractions and packages sent back are also reported for the red badge in the
 * page header.
 *
 * <p>ADDED 2026-10-16: getOverview(logins) can narrow the cards to one person's work: documents
 * uploaded by them, packages assigned to them. The badge numbers (failed, sent back) always count
 * everyone, so a problem is never hidden by the filter.
 *
 * <p>Four queries at most: documents in no package (with the uploaded-today split), packages in
 * Assembly by assignee, the newest submission of every package (same grouped pattern as
 * loadPackages), and, only when something is ready for review, the review flags of the newest
 * extraction job of each document. The newest submission decides the stage of a package.
 */
@Service
@RequiredArgsConstructor
public class PipelineMetricsService {

  private static final String ASSEMBLY = "ASSEMBLY";

  private final EntityManager entityManager;

  /**
   * @param logins login ids of the person whose work to count (any form of the same person's id);
   *     null or empty counts everything
   */
  @Transactional(readOnly = true)
  public PipelineOverview getOverview(final List<String> logins) {
    final Set<String> me = normalize(logins);
    final boolean mineOnly = !me.isEmpty();
    final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    final LocalDateTime startOfDay = now.toLocalDate().atStartOfDay();

    // Card 1: documents in no package. Deleted ones are excluded by @SQLRestriction.
    final TypedQuery<Object[]> docQuery = entityManager.createQuery(
        "select count(d), coalesce(sum(case when d.uploadedAt >= :start then 1 else 0 end), 0) "
            + "from StagedDocument d where not exists "
            + "(select 1 from PackageDocument pd where pd.stagedDocument = d)"
            + (mineOnly ? " and (lower(d.uploadedBy) in :me or lower(d.createdBy) in :me)" : ""),
        Object[].class);
    docQuery.setParameter("start", startOfDay);
    if (mineOnly) {
      docQuery.setParameter("me", me);
    }
    final Object[] docs = docQuery.getSingleResult();

    // Card 2: packages still in assembly, by assignee (summed here so one query serves both modes).
    long inAssembly = 0;
    for (final Object[] row : entityManager
        .createQuery("select p.assigneeId, count(p) from ContractPackage p "
            + "where p.status.code = :code group by p.assigneeId", Object[].class)
        .setParameter("code", ASSEMBLY)
        .getResultList()) {
      if (!mineOnly || isMine(me, (String) row[0])) {
        inAssembly += ((Number) row[1]).longValue();
      }
    }

    long sentBack = 0;
    long sentBackAll = 0;
    long failedAll = 0;
    long queued = 0;
    long reading = 0;
    long ready = 0;
    long awaiting = 0;
    LocalDateTime oldestReviewed = null;
    final List<Long> readyPackageIds = new ArrayList<>();

    for (final Object[] row : entityManager
        .createQuery("select s.contractPackage.id, s.contractPackage.status.code, s.status.code, "
            + "s.reviewedAt, s.updatedAt, s.contractPackage.assigneeId from Submission s "
            + "where s.id in (select max(s2.id) from Submission s2 group by s2.contractPackage.id)",
            Object[].class)
        .getResultList()) {
      final String packageStatus = (String) row[1];
      final String submissionStatus = (String) row[2];
      final boolean mine = !mineOnly || isMine(me, (String) row[5]);
      switch (submissionStatus) {
        case ExtractionStore.SUBMISSION_PENDING -> {
          if (mine) {
            queued++;
          }
        }
        case ExtractionStore.SUBMISSION_PROCESSING -> {
          if (mine) {
            reading++;
          }
        }
        case ExtractionStore.SUBMISSION_EXTRACTED -> {
          if (mine) {
            ready++;
            readyPackageIds.add((Long) row[0]);
          }
        }
        case ExtractionStore.SUBMISSION_FAILED -> failedAll++;
        case ReviewStore.SUBMISSION_DECLINED -> {
          if (ASSEMBLY.equals(packageStatus)) {
            sentBackAll++;
            if (mine) {
              sentBack++;
            }
          }
        }
        case ReviewStore.SUBMISSION_REVIEWED -> {
          if (mine) {
            awaiting++;
            final LocalDateTime since = row[3] != null ? (LocalDateTime) row[3] : (LocalDateTime) row[4];
            if (since != null && (oldestReviewed == null || since.isBefore(oldestReviewed))) {
              oldestReviewed = since;
            }
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
        failedAll,
        sentBackAll);
  }

  /** Lower case, trimmed, without blanks; empty when nothing usable was given. */
  private static Set<String> normalize(final List<String> logins) {
    if (logins == null) {
      return Set.of();
    }
    return logins.stream()
        .filter(s -> s != null && !s.isBlank())
        .map(s -> s.trim().toLowerCase(Locale.ROOT))
        .collect(Collectors.toSet());
  }

  private static boolean isMine(final Set<String> me, final String login) {
    return login != null && me.contains(login.trim().toLowerCase(Locale.ROOT));
  }
}
