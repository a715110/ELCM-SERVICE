package com.dodaso.ecosystem.elcm.packagedetail;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import com.dodaso.ecosystem.elcm.activity.ActivityPage;
import com.dodaso.ecosystem.elcm.activity.ActivityReadService;
import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailAccess;
import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailService;
import com.dodaso.ecosystem.elcm.service.pipeline.AssigneeDirectoryLookupService;
import com.dodaso.ecosystem.elcm.service.pipeline.ContractPackageDataService;
import com.dodaso.ecosystem.elcm.service.pipeline.FileUploadLookupService;
import com.dodaso.ecosystem.elcm.service.pipeline.PackageDocumentDraft;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADDED 2026-10-25 -- Package Details: one package with its documents, submissions and activity
 * log, for the signed-in person if they may see it.
 *
 * <p>Access (the same rule as the Package Rail): administrators, reviewers and approvers may open
 * any package; everybody else only a package they are assigned to, created, submitted or reviewed.
 * A package that does not exist is 404; one the person may not see is 403. Read only: every
 * action stays on the Dashboard and the review dialog.
 */
@Service
@RequiredArgsConstructor
public class PackageDetailService {

  private final PackageDetailDataService dataService;
  private final ContractPackageDataService contractPackageDataService;
  private final FileUploadLookupService fileUploadLookupService;
  private final ActivityReadService activityReadService;
  private final AssigneeDirectoryLookupService directory;

  /** The full detail of a package. */
  public PackageDetail detail(final TaskRailAccess.Caller caller, final Long id) {
    final PackageDetailDataService.Read read = dataService.read(id, caller.login())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such package"));
    if (!TaskRailService.canSeeAll(caller) && !read.involved()) {
      throw forbidden();
    }
    final Map<String, String> names = new HashMap<>();
    final PackageDetailRepository.Header h = read.header();
    final PackageDetail out = new PackageDetail();
    out.setPackageId(h.getId());
    out.setPackageCode(h.getPackageCode());
    out.setStatusCode(h.getStatusCode());
    out.setStatusLabel(h.getStatusLabel());
    out.setWorkspaceCode(h.getWorkspaceCode());
    out.setWorkspaceName(h.getWorkspaceName());
    out.setTargetRecord(h.getTargetRecord());
    out.setAssigneeLoginId(h.getAssigneeId());
    out.setAssignee(nameOf(h.getAssigneeId(), names, "Unassigned"));
    out.setCreatedBy(nameOf(h.getCreatedBy(), names, ""));
    out.setCreatedAt(text(h.getCreatedAt()));
    out.setUpdatedBy(nameOf(h.getUpdatedBy(), names, ""));
    out.setUpdatedAt(text(h.getUpdatedAt()));
    out.setRelation(relation(h, read.submissions(), caller.login()));
    out.setDocuments(documentsOf(id));
    for (final PackageDetailRepository.SubmissionLine s : read.submissions()) {
      out.getSubmissions().add(toSubmission(s, names));
    }
    return out;
  }

  /** One page of the package's activity log (pages start at 0). */
  public ActivityPage activity(final TaskRailAccess.Caller caller, final Long id,
      final Integer page, final Integer size) {
    if (!dataService.exists(id)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such package");
    }
    if (!TaskRailService.canSeeAll(caller) && !dataService.isInvolved(id, caller.login())) {
      throw forbidden();
    }
    return activityReadService.find(id, page, size);
  }

  private static ResponseStatusException forbidden() {
    return new ResponseStatusException(HttpStatus.FORBIDDEN,
        "You do not have access to this package");
  }

  private List<PackageDetail.Document> documentsOf(final Long id) {
    final List<PackageDocumentDraft> drafts = contractPackageDataService.loadPackageDocuments(id);
    final Set<Long> fileIds = new HashSet<>();
    for (final PackageDocumentDraft d : drafts) {
      if (d.getFileUploadId() != null) {
        fileIds.add(d.getFileUploadId());
      }
    }
    final Map<Long, FileUploadDTO> files = fileUploadLookupService.findByIds(fileIds);
    final List<PackageDetail.Document> out = new ArrayList<>();
    for (final PackageDocumentDraft d : drafts) {
      final FileUploadDTO file = d.getFileUploadId() == null ? null : files.get(d.getFileUploadId());
      final PackageDetail.Document row = new PackageDetail.Document();
      row.setStagedDocumentId(d.getStagedDocumentId());
      row.setFileUploadId(d.getFileUploadId());
      row.setFileName(file != null && file.getFileName() != null ? file.getFileName()
          : "(file unavailable)");
      row.setRoleCode(d.getRoleCode());
      row.setRoleLabel(d.getRoleLabel());
      row.setTargetRecord(d.getTargetRecord());
      out.add(row);
    }
    return out;
  }

  private PackageDetail.Submission toSubmission(
      final PackageDetailRepository.SubmissionLine s, final Map<String, String> names) {
    final PackageDetail.Submission out = new PackageDetail.Submission();
    out.setSubmissionId(s.getId());
    out.setStatusCode(s.getStatusCode());
    out.setStatusLabel(s.getStatusLabel());
    out.setSubmittedBy(nameOf(s.getSubmittedBy(), names, ""));
    out.setSubmittedAt(text(s.getSubmittedAt()));
    out.setClaimedBy(nameOf(s.getClaimedBy(), names, ""));
    out.setReviewedBy(nameOf(s.getReviewedBy(), names, ""));
    out.setReviewedAt(text(s.getReviewedAt()));
    out.setDeclineReason(s.getDeclineReason());
    out.setDeclinedBy(nameOf(s.getDeclinedBy(), names, ""));
    out.setDeclinedAt(text(s.getDeclinedAt()));
    out.setCorrectionNote(s.getCorrectionNote());
    return out;
  }

  /** How the viewer is involved, strongest first. */
  static String relation(final PackageDetailRepository.Header header,
      final List<PackageDetailRepository.SubmissionLine> submissions,
      final String login) {
    if (same(header.getAssigneeId(), login)) {
      return "ASSIGNED";
    }
    if (same(header.getCreatedBy(), login)) {
      return "CREATED";
    }
    for (final PackageDetailRepository.SubmissionLine s : submissions) {
      if (same(s.getSubmittedBy(), login)) {
        return "SUBMITTER";
      }
    }
    for (final PackageDetailRepository.SubmissionLine s : submissions) {
      if (same(s.getReviewedBy(), login)) {
        return "REVIEWER";
      }
    }
    return "OTHER";
  }

  private static boolean same(final String a, final String b) {
    return a != null && b != null
        && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
  }

  private static String text(final LocalDateTime time) {
    return time == null ? null : time.toString();
  }

  private String nameOf(final String login, final Map<String, String> cache,
      final String whenBlank) {
    if (login == null || login.isBlank()) {
      return whenBlank;
    }
    return cache.computeIfAbsent(login, key -> directory.findByAssigneeId(key)
        .map(UserDirectoryDTO::getDisplayName)
        .filter(name -> name != null && !name.isBlank())
        .orElse(key));
  }
}
