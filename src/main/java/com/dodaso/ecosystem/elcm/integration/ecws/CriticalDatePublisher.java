package com.dodaso.ecosystem.elcm.integration.ecws;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADDED 2026-11-16 -- queues ECWS requests for critical dates (rows in ecws_task_outbox with event
 * code CRITDATE, a record and no package). Like EcwsTaskPublisher, the row is saved in the
 * caller's transaction and sent later by EcwsTaskDispatcher.
 */
@Service
@RequiredArgsConstructor
public class CriticalDatePublisher {

  private static final String SYSTEM = "system";

  private final EcwsTaskOutboxRepository outbox;

  /** What a new critical date task needs. */
  public record CriticalDateRequest(Long recordId, String recordCode, String sectionKey,
      String summary, String description, String priority, LocalDate dueDate, String assignee,
      String requestor, String documentsText, List<String> members) {
  }

  /** Queues the creation of one critical date task. */
  @Transactional
  public void create(final CriticalDateRequest request) {
    final EcwsTaskOutbox row = row(EcwsTaskOutbox.CREATE, request.recordId(), request.recordCode(),
        request.sectionKey());
    row.setSummary(cut(request.summary(), 200));
    row.setDescription(cut(request.description(), 500));
    row.setPriority(request.priority());
    row.setDueDate(request.dueDate());
    row.setAssigneeLogin(blankToNull(request.assignee()));
    // REVISED 2026-11-17: the person who submitted the package; "system" only as a last resort.
    row.setRequestorLogin(blankToNull(request.requestor()) == null ? SYSTEM
        : request.requestor().trim());
    row.setDocumentsText(blankToNull(request.documentsText()));
    row.setMemberLogins(EcwsTaskPublisher.joinLogins(request.members()));
    row.setTaskOpen(true);
    outbox.save(row);
  }

  /** Queues the closing of one critical date task, when ELCM still has it open. */
  @Transactional
  public void close(final Long recordId, final String recordCode, final String sectionKey,
      final String comment, final List<String> members) {
    if (outbox.closeOpenForRecord(recordId, sectionKey) > 0) {
      final EcwsTaskOutbox row = row(EcwsTaskOutbox.CLOSE, recordId, recordCode, sectionKey);
      row.setCommentText(cut(comment, 500));
      row.setMemberLogins(EcwsTaskPublisher.joinLogins(members));
      outbox.save(row);
    }
  }

  private static EcwsTaskOutbox row(final String action, final Long recordId,
      final String recordCode, final String sectionKey) {
    final EcwsTaskOutbox row = new EcwsTaskOutbox();
    row.setAction(action);
    row.setEventCode(EcwsTaskPublisher.CRITDATE);
    row.setRecordId(recordId);
    row.setSectionKey(sectionKey);
    row.setPackageCode(recordCode);
    row.setStatus(EcwsTaskOutbox.PENDING);
    row.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC));
    row.setCreatedBy(SYSTEM);
    return row;
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
