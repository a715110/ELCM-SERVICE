package com.dodaso.ecosystem.elcm.integration.ecws;

import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import com.dodaso.ecosystem.elcm.entity.pipeline.PackageDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.StagedDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractPackageRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import com.dodaso.ecosystem.elcm.service.pipeline.AssigneeDirectoryLookupService;
import com.dodaso.ecosystem.elcm.service.pipeline.FileUploadLookupService;
import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADDED 2026-10-16 -- turns important ELCM events into queued ECWS requests (rows in
 * ecws_task_outbox). Called from ExtractionStore, ReviewStore, ApplyStore and
 * ContractPackageDataService, inside their own transaction, so a request is queued if and only if
 * the ELCM action is saved. Sending happens later (EcwsTaskDispatcher).
 *
 * <p>Every public method returns quietly when the integration is off. When the package cannot be
 * read it logs and skips, and when nobody qualifies as assignee the task is queued unassigned.
 * Because a request is saved in the caller's transaction, the outbox table must exist before the
 * integration is turned on (elcm_ecws_task_outbox.sql).
 *
 * <p>Events and task kinds: REVIEW (review the extracted package), APPROVAL (approve a reviewed
 * package), FIX (correct a declined or sent-back package), FAILED (extraction failed).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EcwsTaskPublisher {

  public static final String REVIEW = "REVIEW";
  public static final String APPROVAL = "APPROVAL";
  public static final String FIX = "FIX";
  public static final String FAILED = "FAILED";

  private static final String MEDIUM = "Medium";
  private static final String HIGH = "High";
  private static final int MAX_REASON = 200;

  private final EcwsIntegrationProperties props;
  private final EcwsTaskOutboxRepository outbox;
  private final EcwsAssigneePicker picker;
  private final ContractPackageRepository contractPackageRepository;
  private final SubmissionRepository submissionRepository;
  private final PackageDocumentRepository packageDocumentRepository;
  private final AssigneeDirectoryLookupService directory;
  private final FileUploadLookupService fileUploadLookupService;

  /** Extraction finished well: the package is ready for review. */
  @Transactional
  public void reviewReady(final Long packageId) {
    if (!props.isEnabled()) {
      return;
    }
    final Ctx c = context(packageId);
    if (c == null) {
      return;
    }
    close(c, FAILED, "Extraction succeeded in ELCM");
    final String assignee = picker.pick(props.getReviewerRole(), excluding(c.submitter)).orElse(null);
    final long documents = packageDocumentRepository.countByContractPackage_Id(packageId);
    create(c, REVIEW, "Review package " + c.code, MEDIUM, assignee, c.submitter,
        "Package " + c.code + " (workspace " + c.workspace + ") is ready for review. " + documents
            + " document(s), submitted by " + nz(c.submitter) + ". Open: " + reviewLink(packageId));
  }

  /** Extraction failed: the submitter has to retry or decline. */
  @Transactional
  public void extractionFailed(final Long packageId) {
    if (!props.isEnabled()) {
      return;
    }
    final Ctx c = context(packageId);
    if (c == null) {
      return;
    }
    close(c, FAILED, "A new extraction failure replaced this task");
    create(c, FAILED, "Extraction failed for package " + c.code, HIGH, blankToNull(c.submitter),
        "system", "Extraction failed for package " + c.code + " (workspace " + c.workspace
            + "). Open the package in ELCM, then Retry or Decline it. Open: " + reviewLink(packageId));
  }

  /** Retry was pressed: the failure task is done. */
  @Transactional
  public void retried(final Long packageId) {
    if (!props.isEnabled()) {
      return;
    }
    final Ctx c = context(packageId);
    if (c != null) {
      close(c, FAILED, "Retry started in ELCM");
    }
  }

  /** The reviewer completed the review: the approver is next. */
  @Transactional
  public void reviewCompleted(final Long packageId, final String reviewer) {
    if (!props.isEnabled()) {
      return;
    }
    final Ctx c = context(packageId);
    if (c == null) {
      return;
    }
    close(c, REVIEW, "Completed in ELCM by " + nz(reviewer));
    close(c, FIX, "Review completed again in ELCM by " + nz(reviewer));
    final List<String> excluded = new ArrayList<>(excluding(c.submitter));
    excluded.add(reviewer);
    final String assignee = picker.pick(props.getApproverRole(), excluded).orElse(null);
    create(c, APPROVAL, "Approve package " + c.code, MEDIUM, assignee, reviewer,
        "Review of package " + c.code + " (workspace " + c.workspace + ") was completed by "
            + nz(reviewer) + ". Approve it, or send it back with a reason. Open: " + reviewLink(packageId));
  }

  /** The reviewer reopened a completed review: back to review. */
  @Transactional
  public void reviewReopened(final Long packageId, final String by) {
    if (!props.isEnabled()) {
      return;
    }
    final Ctx c = context(packageId);
    if (c == null) {
      return;
    }
    close(c, APPROVAL, "Review reopened in ELCM by " + nz(by));
    final String assignee = picker.pick(props.getReviewerRole(), excluding(c.submitter)).orElse(null);
    create(c, REVIEW, "Review package " + c.code, MEDIUM, assignee, by,
        "The review of package " + c.code + " (workspace " + c.workspace + ") was reopened by "
            + nz(by) + ". Open: " + reviewLink(packageId));
  }

  /** The approver sent the package back: review again, and tell the person in charge. */
  @Transactional
  public void sentBack(final Long packageId, final String by, final String reason) {
    if (!props.isEnabled()) {
      return;
    }
    final Ctx c = context(packageId);
    if (c == null) {
      return;
    }
    close(c, APPROVAL, "Sent back in ELCM by " + nz(by));
    final String why = cut(reason, MAX_REASON);
    final String reviewer = picker.pick(props.getReviewerRole(), excluding(c.submitter)).orElse(null);
    create(c, REVIEW, "Review package " + c.code + " again", MEDIUM, reviewer, by,
        "Package " + c.code + " (workspace " + c.workspace + ") was sent back by " + nz(by)
            + ": " + why + " Open: " + reviewLink(packageId));
    create(c, FIX, "Fix package " + c.code, HIGH, c.assigneeLogin, by,
        "Package " + c.code + " (workspace " + c.workspace + ") was sent back by " + nz(by)
            + ": " + why + " Open: " + link(packageId));
  }

  /** The package was declined back to Assembly: the person in charge has to fix it. */
  @Transactional
  public void declined(final Long packageId, final String by, final String reason) {
    if (!props.isEnabled()) {
      return;
    }
    final Ctx c = context(packageId);
    if (c == null) {
      return;
    }
    final String note = "Declined in ELCM by " + nz(by);
    close(c, REVIEW, note);
    close(c, APPROVAL, note);
    close(c, FAILED, note);
    close(c, FIX, note);
    create(c, FIX, "Fix package " + c.code, HIGH, c.assigneeLogin, by,
        "Package " + c.code + " (workspace " + c.workspace + ") was declined by " + nz(by) + ": "
            + cut(reason, MAX_REASON) + " Correct it and submit it again. Open: " + link(packageId));
  }

  /** The reviewed values were applied: nothing is left to do. */
  @Transactional
  public void applied(final Long packageId, final String by) {
    if (!props.isEnabled()) {
      return;
    }
    final Ctx c = context(packageId);
    if (c == null) {
      return;
    }
    final String note = "Applied in ELCM by " + nz(by);
    close(c, APPROVAL, note);
    close(c, REVIEW, note);
    close(c, FIX, note);
  }

  /** The package was submitted again: the fix task is done. */
  @Transactional
  public void resubmitted(final Long packageId, final String by) {
    if (!props.isEnabled()) {
      return;
    }
    final Ctx c = context(packageId);
    if (c != null) {
      close(c, FIX, "Submitted again in ELCM by " + nz(by));
    }
  }

  /** The package got a new assignee: its open fix task moves with it. */
  @Transactional
  public void reassigned(final Long packageId, final String newAssigneeId) {
    if (!props.isEnabled()) {
      return;
    }
    final Ctx c = context(packageId);
    if (c == null) {
      return;
    }
    final String login = loginOf(newAssigneeId);
    if (outbox.reassignOpen(packageId, FIX, login) > 0) {
      final EcwsTaskOutbox row = row(EcwsTaskOutbox.REASSIGN, FIX, c);
      row.setAssigneeLogin(login);
      row.setCommentText("Package reassigned in ELCM");
      outbox.save(row);
    }
  }

  // ---- building and queueing ----

  /** Who and what a request is about, read once per call. */
  private record Ctx(Long packageId, String code, String workspace, String submitter,
                     String assigneeLogin) {
  }

  private Ctx context(final Long packageId) {
    try {
      final ContractPackage pkg = contractPackageRepository.findById(packageId).orElse(null);
      if (pkg == null) {
        return null;
      }
      final Optional<Submission> newest = submissionRepository.findByContractPackage_Id(packageId)
          .stream().max(Comparator.comparing(Submission::getId));
      final String workspace = pkg.getWorkspace() == null ? "" : nz(pkg.getWorkspace().getName());
      return new Ctx(packageId, nz(pkg.getPackageCode()), workspace,
          newest.map(Submission::getSubmittedBy).orElse(null), loginOf(pkg.getAssigneeId()));
    } catch (final RuntimeException e) {
      log.warn("ECWS: could not read package {} for a task request; skipped", packageId, e);
      return null;
    }
  }

  private void create(final Ctx c, final String event, final String summary, final String priority,
                      final String assignee, final String requestor, final String description) {
    final EcwsTaskOutbox row = row(EcwsTaskOutbox.CREATE, event, c);
    row.setSummary(cut(summary, 200));
    row.setDescription(cut(description, 500));
    row.setPriority(priority);
    row.setDueDate(dueDateFor(event));
    if (REVIEW.equals(event) || APPROVAL.equals(event)) {
      row.setDocumentsText(documentsText(c.packageId));
    }
    row.setAssigneeLogin(blankToNull(assignee));
    row.setRequestorLogin(blankToNull(requestor));
    row.setTaskOpen(true);
    outbox.save(row);
  }

  private void close(final Ctx c, final String event, final String comment) {
    if (outbox.closeOpen(c.packageId, event) > 0) {
      final EcwsTaskOutbox row = row(EcwsTaskOutbox.CLOSE, event, c);
      row.setCommentText(cut(comment, 500));
      outbox.save(row);
    }
  }

  private EcwsTaskOutbox row(final String action, final String event, final Ctx c) {
    final EcwsTaskOutbox row = new EcwsTaskOutbox();
    row.setAction(action);
    row.setEventCode(event);
    row.setPackageId(c.packageId);
    row.setPackageCode(c.code);
    row.setStatus(EcwsTaskOutbox.PENDING);
    row.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC));
    row.setCreatedBy("system");
    return row;
  }

  // ---- small helpers ----

  /**
   * ADDED 2026-10-16 -- one line per package document, "name TAB link", for ECWS to show as
   * hyperlinks. The link opens the document in the ELCM viewer (login required) and carries the same
   * details the dashboard's View button passes, so the viewer's Document panel is filled in. At most
   * documentLinksMax lines, then a line saying how many more. Null when the list is off, the package
   * has no documents, or anything fails: the task is then created without the list.
   */
  private String documentsText(final Long packageId) {
    final int max = props.getDocumentLinksMax();
    if (max <= 0) {
      return null;
    }
    try {
      final Map<Long, StagedDocument> docs = new LinkedHashMap<>();
      for (final PackageDocument link : packageDocumentRepository.findDetailedByPackageId(packageId)) {
        final StagedDocument doc = link.getStagedDocument();
        if (doc.getFileUploadId() != null) {
          docs.putIfAbsent(doc.getFileUploadId(), doc);
        }
      }
      if (docs.isEmpty()) {
        return null;
      }
      final Map<Long, FileUploadDTO> files = fileUploadLookupService.findByIds(docs.keySet());
      final StringBuilder text = new StringBuilder();
      int number = 0;
      for (final Map.Entry<Long, StagedDocument> entry : docs.entrySet()) {
        number++;
        if (number > max) {
          text.append("and ").append(docs.size() - max).append(" more, see the package in ELCM\n");
          break;
        }
        final FileUploadDTO file = files.get(entry.getKey());
        final String name = file == null || file.getFileName() == null ? "document " + entry.getKey()
            : file.getFileName();
        text.append(number).append(". ").append(name).append('\t')
            .append(viewerLink(entry.getValue(), name)).append('\n');
      }
      return cut(text.toString(), 4000);
    } catch (final RuntimeException e) {
      log.warn("ECWS: could not list the documents of package {}; task created without them",
          packageId, e);
      return null;
    }
  }

  /** Same query parameters as the dashboard's View button (dashboard.xhtml), same values. */
  private String viewerLink(final StagedDocument doc, final String fileName) {
    String base = props.getUiBaseUrl() == null ? "" : props.getUiBaseUrl().trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    final int dot = fileName.lastIndexOf('.');
    final String type = dot >= 0 && dot < fileName.length() - 1
        ? fileName.substring(dot + 1).toUpperCase(java.util.Locale.ROOT) : "";
    final StringBuilder url = new StringBuilder(base).append("/documentviewer?fileUploadId=")
        .append(doc.getFileUploadId());
    if (doc.getTargetRecord() != null) {
      param(url, "recordId", String.valueOf(doc.getTargetRecord().getId()));
    }
    param(url, "fileName", fileName);
    param(url, "type", type);
    param(url, "workspace", doc.getWorkspace() == null ? null : doc.getWorkspace().getCode());
    param(url, "record", recordLabel(doc));
    param(url, "assignee", assigneeLabel(doc.getAssigneeId()));
    param(url, "uploadedAt", doc.getUploadedAt() == null ? null
        : doc.getUploadedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " UTC");
    return url.toString();
  }

  private static void param(final StringBuilder url, final String name, final String value) {
    if (value != null && !value.isBlank()) {
      url.append('&').append(name).append('=').append(encode(value));
    }
  }

  /** Same order as the dashboard's Record column (StageDocumentService.deriveRecordLabel). */
  private static String recordLabel(final StagedDocument doc) {
    if (doc.getTargetRecord() != null) {
      return doc.getTargetRecord().getRecordCode();
    }
    for (final String candidate : new String[] {doc.getNewRecordName(),
        doc.getNewRecordCounterparty(), doc.getExistingRecordQuery()}) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return "Unassigned";
  }

  /** The assignee's display name when the directory knows them, else the stored value. */
  private String assigneeLabel(final String assigneeId) {
    if (assigneeId == null || assigneeId.isBlank()) {
      return "Unassigned";
    }
    return directory.findByAssigneeId(assigneeId).map(UserDirectoryDTO::getDisplayName)
        .filter(name -> name != null && !name.isBlank()).orElse(assigneeId);
  }

  private static String encode(final String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** Due date for a new task of this kind, or null when its setting is 0 or less. */
  private LocalDate dueDateFor(final String event) {
    final EcwsIntegrationProperties.DueDays days = props.getDueDays();
    final int count = switch (event) {
      case REVIEW -> days.getReview();
      case APPROVAL -> days.getApproval();
      case FIX -> days.getFix();
      case FAILED -> days.getFailed();
      default -> 0;
    };
    return count <= 0 ? null : addBusinessDays(LocalDate.now(ZoneOffset.UTC), count);
  }

  /** The date that many business days (Monday to Friday) after the given day. Holidays are ignored. */
  static LocalDate addBusinessDays(final LocalDate from, final int days) {
    LocalDate date = from;
    int left = days;
    while (left > 0) {
      date = date.plusDays(1);
      if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
        left--;
      }
    }
    return date;
  }

  /** The Dashboard with the package; for a task about a draft that must be fixed there. */
  private String link(final Long packageId) {
    return baseUrl() + "/dashboard?review=" + packageId;
  }

  /**
   * ADDED 2026-10-25: the Package Review page of the package, for Review, Approval and extraction
   * tasks. Links already written into earlier tasks (dashboard with review) keep working.
   */
  private String reviewLink(final Long packageId) {
    return baseUrl() + "/packagereview?package=" + packageId;
  }

  private String baseUrl() {
    String base = props.getUiBaseUrl() == null ? "" : props.getUiBaseUrl().trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
  }

  /** The login id for a stored assignee (a login id, or on old rows a display name); null if unknown. */
  private String loginOf(final String assigneeId) {
    if (assigneeId == null || assigneeId.isBlank()) {
      return null;
    }
    return directory.findByAssigneeId(assigneeId).map(UserDirectoryDTO::getLoginId).orElse(null);
  }

  private static List<String> excluding(final String submitter) {
    final List<String> list = new ArrayList<>();
    if (submitter != null) {
      list.add(submitter);
    }
    return list;
  }

  private static String nz(final String s) {
    return s == null ? "" : s;
  }

  private static String blankToNull(final String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static String cut(final String s, final int max) {
    if (s == null) {
      return "";
    }
    final String t = s.trim();
    return t.length() <= max ? t : t.substring(0, max - 3) + "...";
  }
}
