package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of the work queue: one read-only transaction, no outbound calls. The queue has
 * one line per package that is submitted or applied, taken from the package's newest submission. A
 * package that was declined is back in Assembly and is not in the queue until it is submitted
 * again.
 */
@Service
@RequiredArgsConstructor
public class WorkQueueDataService {

  static final String EXTRACTING = "EXTRACTING";
  static final String REVIEW = "REVIEW";
  static final String APPROVAL = "APPROVAL";
  static final String FAILED = "FAILED";
  static final String APPLIED = "APPLIED";

  private final SubmissionRepository submissionRepository;
  private final PackageDocumentRepository packageDocumentRepository;

  @Transactional(readOnly = true)
  public List<WorkQueueItem> load() {
    final List<WorkQueueItem> items = new ArrayList<>();
    final Set<Long> seen = new HashSet<>();
    // Newest submission first, so the first one seen for a package is the one that counts.
    for (final Submission s : submissionRepository.findForWorkQueue()) {
      final ContractPackage pkg = s.getContractPackage();
      final String stage = stageOf(s.getStatus().getCode());
      if (stage == null || !seen.add(pkg.getId())) {
        continue;
      }
      items.add(
          new WorkQueueItem(
              pkg.getId(),
              pkg.getPackageCode(),
              pkg.getWorkspace().getCode(),
              pkg.getTargetRecord() != null ? pkg.getTargetRecord().getRecordCode() : "Unassigned",
              (int) packageDocumentRepository.countByContractPackage_Id(pkg.getId()),
              pkg.getAssigneeId(),
              pkg.getAssigneeId(),
              s.getSubmittedBy(),
              s.getSubmittedBy(),
              s.getReviewedBy(),
              s.getStatus().getCode(),
              stage,
              labelOf(stage),
              iso(s.getUpdatedAt()),
              iso(s.getSubmittedAt())));
    }
    return items;
  }

  /** The queue bucket of a submission status; null for a status that has no place in the queue. */
  static String stageOf(final String submissionStatusCode) {
    if (submissionStatusCode == null) {
      return null;
    }
    return switch (submissionStatusCode) {
      case "PENDING", "PROCESSING" -> EXTRACTING;
      case "EXTRACTED" -> REVIEW;
      case "REVIEWED" -> APPROVAL;
      case "FAILED" -> FAILED;
      case "APPLIED" -> APPLIED;
      default -> null;
    };
  }

  static String labelOf(final String stage) {
    return switch (stage) {
      case EXTRACTING -> "Extracting";
      case REVIEW -> "Ready for review";
      case APPROVAL -> "Awaiting approval";
      case FAILED -> "Failed";
      case APPLIED -> "Applied";
      default -> stage;
    };
  }

  private static String iso(final LocalDateTime time) {
    return time == null ? null : time.toString();
  }
}
