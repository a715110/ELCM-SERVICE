package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import com.dodaso.ecosystem.elcm.entity.pipeline.PackageDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of the My Submissions screen: one read-only transaction per call, no outbound
 * calls. File names are added later by {@link MySubmissionsService}.
 */
@Service
@RequiredArgsConstructor
public class MySubmissionsDataService {

  static final String PENDING = "PENDING";
  static final String IN_PROGRESS = "IN_PROGRESS";
  static final String COMPLETED = "COMPLETED";
  static final String DECLINED = "DECLINED";

  private final SubmissionRepository submissionRepository;
  private final PackageDocumentRepository packageDocumentRepository;

  /** Every submission the person made, newest first (declined ones stay as history). */
  @Transactional(readOnly = true)
  public List<MySubmissionItem> loadSubmissions(final String login) {
    final List<MySubmissionItem> items = new ArrayList<>();
    for (final Submission s : submissionRepository.findBySubmitterDetailed(login)) {
      final ContractPackage pkg = s.getContractPackage();
      final String code = s.getStatus().getCode();
      items.add(
          new MySubmissionItem(
              s.getId(),
              pkg.getId(),
              pkg.getPackageCode(),
              pkg.getWorkspace().getCode(),
              pkg.getTargetRecord() != null ? pkg.getTargetRecord().getRecordCode() : "Unassigned",
              (int) packageDocumentRepository.countByContractPackage_Id(pkg.getId()),
              code,
              s.getStatus().getLabel(),
              groupOf(code),
              iso(s.getSubmittedAt()),
              iso(s.getUpdatedAt()),
              s.getCorrectionNote(),
              s.getDeclineReason(),
              s.getDeclinedBy(),
              iso(s.getDeclinedAt())));
    }
    return items;
  }

  /**
   * The documents of the person's applied submissions, newest first. A package has at most one
   * applied submission, so each document is listed once.
   */
  @Transactional(readOnly = true)
  public List<MyCommittedDocument> loadCommitted(final String login) {
    final List<MyCommittedDocument> docs = new ArrayList<>();
    for (final Submission s : submissionRepository.findBySubmitterDetailed(login)) {
      if (!"APPLIED".equals(s.getStatus().getCode())) {
        continue;
      }
      final ContractPackage pkg = s.getContractPackage();
      for (final PackageDocument pd :
          packageDocumentRepository.findDetailedByPackageId(pkg.getId())) {
        docs.add(
            new MyCommittedDocument(
                pd.getStagedDocument().getId(),
                pd.getStagedDocument().getFileUploadId(),
                null,
                pd.getDocumentRole().getLabel(),
                pkg.getId(),
                pkg.getPackageCode(),
                pkg.getWorkspace().getCode(),
                pkg.getTargetRecord() != null
                    ? pkg.getTargetRecord().getRecordCode()
                    : "Unassigned",
                iso(s.getUpdatedAt())));
      }
    }
    return docs;
  }

  /** The filter bucket of a submission status code. */
  static String groupOf(final String statusCode) {
    if (statusCode == null) {
      return IN_PROGRESS;
    }
    return switch (statusCode) {
      case "PENDING" -> PENDING;
      case "APPLIED" -> COMPLETED;
      case "DECLINED" -> DECLINED;
      default -> IN_PROGRESS;
    };
  }

  private static String iso(final LocalDateTime time) {
    return time == null ? null : time.toString();
  }
}
