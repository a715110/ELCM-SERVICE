package com.dodaso.ecosystem.elcm.integration.ecws;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADDED 2026-10-18 -- the database side of the Task Rail: one read-only transaction, no outbound
 * calls. It reads the task rows ELCM already keeps in ecws_task_outbox (so it works even when ECWS
 * is down), applies the access rules of {@link TaskRailAccess}, and for closed tasks adds the time
 * and outcome from the matching CLOSE row. Display names are added later by TaskRailService.
 *
 * <p>Only Review and Approval tasks are shown. Fix and Extraction failed tasks still go to ECWS but
 * are not part of the rail.
 */
@Service
@RequiredArgsConstructor
public class TaskRailDataService {

  static final List<String> EVENTS = List.of(EcwsTaskPublisher.REVIEW, EcwsTaskPublisher.APPROVAL);

  /** Most open tasks read for one person. Open tasks are few, this is only a safety stop. */
  static final int OPEN_LIMIT = 200;

  /** Rows read per round when filling a page of closed tasks, and how many rounds at most. */
  static final int CLOSED_BATCH = 60;
  static final int CLOSED_ROUNDS = 5;

  private final EcwsTaskOutboxRepository outbox;
  private final TaskRailSubmissionRepository submissions;

  /** A task row with the relation under which the caller sees it. */
  public record Seen(EcwsTaskOutbox row, String relation, LocalDateTime closedAt,
                     String outcome) {
  }

  /** A page of closed tasks and the cursor for the next page (null when there is no more). */
  public record ClosedPage(List<Seen> items, Long nextBefore) {
  }

  @Transactional(readOnly = true)
  public List<Seen> open(final TaskRailAccess.Caller caller, final boolean mineOnly) {
    final List<EcwsTaskOutbox> rows = caller.admin()
        ? outbox.findOpenForRailAll(EVENTS, PageRequest.of(0, OPEN_LIMIT))
        : outbox.findOpenForRailPerson(EVENTS, key(caller.login()),
            PageRequest.of(0, OPEN_LIMIT));
    return filter(caller, true, mineOnly, rows);
  }

  @Transactional(readOnly = true)
  public ClosedPage closed(final TaskRailAccess.Caller caller, final boolean mineOnly,
      final long before, final int size, final int days) {
    final LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
    final List<EcwsTaskOutbox> picked = new ArrayList<>();
    final List<String> relations = new ArrayList<>();
    long cursor = before;
    boolean more = false;
    for (int round = 0; round < CLOSED_ROUNDS && picked.size() < size; round++) {
      final List<EcwsTaskOutbox> batch = caller.admin()
          ? outbox.findClosedForRailAll(EVENTS, since, cursor, PageRequest.of(0, CLOSED_BATCH))
          : outbox.findClosedForRailPerson(EVENTS, since, cursor, key(caller.login()),
              PageRequest.of(0, CLOSED_BATCH));
      if (batch.isEmpty()) {
        more = false;
        break;
      }
      final Map<Long, String[]> people = peopleOf(batch);
      boolean full = false;
      for (int i = 0; i < batch.size(); i++) {
        final EcwsTaskOutbox row = batch.get(i);
        cursor = row.getId();
        final String[] who = people.getOrDefault(row.getPackageId(), new String[2]);
        final String relation = TaskRailAccess.relation(caller, false, row.getEventCode(),
            row.getAssigneeLogin(), who[0], who[1]);
        if (relation == null || (mineOnly && !TaskRailAccess.isMine(relation))) {
          continue;
        }
        picked.add(row);
        relations.add(relation);
        if (picked.size() == size) {
          more = i < batch.size() - 1 || batch.size() == CLOSED_BATCH;
          full = true;
          break;
        }
      }
      if (full) {
        break;
      }
      more = batch.size() == CLOSED_BATCH;
      if (!more) {
        break;
      }
    }
    return new ClosedPage(withCloseInfo(picked, relations), more ? cursor : null);
  }

  /** How many open Review and Approval tasks are assigned to the person. */
  @Transactional(readOnly = true)
  public int countAssigned(final String login) {
    if (login == null || login.isBlank()) {
      return 0;
    }
    return (int) outbox.countByActionAndTaskOpenTrueAndEventCodeInAndAssigneeLoginIgnoreCase(
        EcwsTaskOutbox.CREATE, EVENTS, login.trim());
  }

  // ---- helpers ----

  private List<Seen> filter(final TaskRailAccess.Caller caller, final boolean open,
      final boolean mineOnly, final List<EcwsTaskOutbox> rows) {
    final Map<Long, String[]> people = peopleOf(rows);
    final List<Seen> out = new ArrayList<>();
    for (final EcwsTaskOutbox row : rows) {
      final String[] who = people.getOrDefault(row.getPackageId(), new String[2]);
      final String relation = TaskRailAccess.relation(caller, open, row.getEventCode(),
          row.getAssigneeLogin(), who[0], who[1]);
      if (relation != null && (!mineOnly || TaskRailAccess.isMine(relation))) {
        out.add(new Seen(row, relation, null, null));
      }
    }
    return out;
  }

  /**
   * Submitter and reviewer of each package, from its newest submission: package id to
   * [submittedBy, reviewedBy].
   */
  private Map<Long, String[]> peopleOf(final Collection<EcwsTaskOutbox> rows) {
    final Set<Long> ids = new HashSet<>();
    for (final EcwsTaskOutbox row : rows) {
      ids.add(row.getPackageId());
    }
    final Map<Long, String[]> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    final Map<Long, Long> newest = new HashMap<>();
    for (final TaskRailSubmissionRepository.Line line : submissions.findLines(ids)) {
      final Long seen = newest.get(line.getPackageId());
      if (seen == null || line.getSubmissionId() > seen) {
        newest.put(line.getPackageId(), line.getSubmissionId());
        out.put(line.getPackageId(), new String[] {line.getSubmittedBy(), line.getReviewedBy()});
      }
    }
    return out;
  }

  /**
   * Adds the closing time and outcome: the first CLOSE row of the same package and event after the
   * task.
   */
  private List<Seen> withCloseInfo(final List<EcwsTaskOutbox> rows, final List<String> relations) {
    final List<Seen> out = new ArrayList<>();
    if (rows.isEmpty()) {
      return out;
    }
    final Set<Long> packages = new HashSet<>();
    for (final EcwsTaskOutbox row : rows) {
      packages.add(row.getPackageId());
    }
    final List<EcwsTaskOutbox> closes = outbox.findCloseRows(packages, EVENTS);
    for (int i = 0; i < rows.size(); i++) {
      final EcwsTaskOutbox row = rows.get(i);
      EcwsTaskOutbox close = null;
      for (final EcwsTaskOutbox candidate : closes) {
        if (candidate.getPackageId().equals(row.getPackageId())
            && candidate.getEventCode().equals(row.getEventCode())
            && candidate.getId() > row.getId()) {
          close = candidate;
          break;
        }
      }
      // next_attempt_at of a new row is set from the code clock in UTC, so it is the closing time.
      out.add(new Seen(row, relations.get(i),
          close != null ? close.getNextAttemptAt() : row.getUpdatedAt(),
          close != null ? close.getCommentText() : null));
    }
    return out;
  }

  private static String key(final String login) {
    return login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
  }
}
