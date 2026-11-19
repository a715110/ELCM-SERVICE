package com.dodaso.ecosystem.elcm.criticaldate;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.elcm.criticaldate.CriticalDateRules.Decision;
import com.dodaso.ecosystem.elcm.entity.criticaldate.CriticalDateRule;
import com.dodaso.ecosystem.elcm.entity.criticaldate.CriticalDateTask;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecord;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractTerm;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.integration.ecws.CriticalDatePublisher;
import com.dodaso.ecosystem.elcm.integration.ecws.CriticalDatePublisher.CriticalDateRequest;
import com.dodaso.ecosystem.elcm.integration.ecws.EcwsAssigneePicker;
import com.dodaso.ecosystem.elcm.integration.ecws.EcwsIntegrationProperties;
import com.dodaso.ecosystem.elcm.repository.criticaldate.CriticalDateRuleRepository;
import com.dodaso.ecosystem.elcm.repository.criticaldate.CriticalDateTaskRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractRecordRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractTermRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import com.dodaso.ecosystem.elcm.service.pipeline.AssigneeDirectoryLookupService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADDED 2026-11-16 -- turns the dates written in contracts into ECWS tasks.
 *
 * <p>For each enabled rule (table critical_date_rule) and each live contract record (status ACTIVE
 * or PENDING) it looks at the term in force. When today is within leadDays of the due date, one
 * task is queued for ECWS, due on that date (see CriticalDatePublisher). The task goes to the
 * person in charge of the package that supplied the date, else to the least busy holder of the
 * approver role. Every date gets one task: critical_date_task remembers it.
 *
 * <p>A task is retired (and closed in ECWS) when its date no longer stands: a later Apply replaced
 * the term, the notice period moved the due date, or the record left the live statuses. The new
 * date then gets its own task when its time comes.
 *
 * <p>When it runs: right after an Apply commits (recordChanged), and every morning for all records
 * (CriticalDateJob), which also repairs anything the first path missed. Each record is handled in
 * its own transaction, so one bad record never blocks the others. Nothing happens unless both
 * elcm.critical-dates.enabled and elcm.ecws.enabled are true.
 */
@Service
@Slf4j
public class CriticalDateService {

  /** Record statuses that count as live (the same ones an Apply may write to). */
  private static final Set<String> LIVE_STATUS_CODES = Set.of("ACTIVE", "PENDING");
  private static final String SYSTEM = "system";

  private final CriticalDateProperties props;
  private final EcwsIntegrationProperties ecwsProps;
  private final CriticalDateRuleRepository ruleRepository;
  private final CriticalDateTaskRepository taskRepository;
  private final ContractTermRepository termRepository;
  private final ContractRecordRepository recordRepository;
  private final SubmissionRepository submissionRepository;
  private final AssigneeDirectoryLookupService directory;
  private final EcwsAssigneePicker picker;
  private final CriticalDatePublisher publisher;
  private final TransactionTemplate ownTransaction;

  public CriticalDateService(final CriticalDateProperties props,
      final EcwsIntegrationProperties ecwsProps, final CriticalDateRuleRepository ruleRepository,
      final CriticalDateTaskRepository taskRepository, final ContractTermRepository termRepository,
      final ContractRecordRepository recordRepository,
      final SubmissionRepository submissionRepository,
      final AssigneeDirectoryLookupService directory, final EcwsAssigneePicker picker,
      final CriticalDatePublisher publisher, final PlatformTransactionManager transactionManager) {
    this.props = props;
    this.ecwsProps = ecwsProps;
    this.ruleRepository = ruleRepository;
    this.taskRepository = taskRepository;
    this.termRepository = termRepository;
    this.recordRepository = recordRepository;
    this.submissionRepository = submissionRepository;
    this.directory = directory;
    this.picker = picker;
    this.publisher = publisher;
    this.ownTransaction = new TransactionTemplate(transactionManager);
    this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * A record was applied. Its critical dates are checked once the caller's transaction commits (or
   * at once when there is none). A failure is logged and left to the morning check.
   */
  public void recordChanged(final Long recordId, final String by) {
    if (!isActive() || recordId == null) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          syncSafely(recordId);
        }
      });
    } else {
      syncSafely(recordId);
    }
  }

  /** Checks every record that has a date to watch or an open task. Returns how many were looked at. */
  public int checkAll() {
    if (!isActive()) {
      return 0;
    }
    final Set<Long> ids = new LinkedHashSet<>(recordIdsToCheck());
    for (final Long id : ids) {
      syncSafely(id);
    }
    return ids.size();
  }

  /** Ids of the records holding a date term of an enabled rule, plus those with an open task. */
  Set<Long> recordIdsToCheck() {
    final Set<String> keys = new HashSet<>();
    for (final CriticalDateRule rule : ruleRepository.findByEnabledTrueOrderByIdAsc()) {
      keys.add(rule.getFieldKey());
    }
    final Set<Long> ids = new LinkedHashSet<>();
    if (!keys.isEmpty()) {
      for (final ContractTerm term : termRepository.findBySupersededAtIsNullAndFieldKeyIn(keys)) {
        ids.add(term.getContractRecord().getId());
      }
    }
    ids.addAll(taskRepository.findRecordIdsWithOpenTasks());
    return ids;
  }

  private boolean isActive() {
    return props.isEnabled() && ecwsProps.isEnabled();
  }

  private void syncSafely(final Long recordId) {
    try {
      ownTransaction.executeWithoutResult(status -> sync(recordId));
    } catch (final RuntimeException e) {
      log.warn("CRITICAL-DATES record {} could not be checked; the morning check will retry",
          recordId, e);
    }
  }

  /** The whole check of one record. Runs inside its own transaction. */
  void sync(final Long recordId) {
    final ContractRecord record = recordRepository.findById(recordId).orElse(null);
    final boolean live = record != null && isLive(record);
    final Map<String, ContractTerm> terms = new HashMap<>();
    if (live) {
      for (final ContractTerm term : termRepository
          .findByContractRecord_IdAndSupersededAtIsNull(recordId)) {
        terms.put(term.getFieldKey(), term);
      }
    }
    final Map<Long, CriticalDateRule> rules = new HashMap<>();
    final List<CriticalDateRule> enabled = ruleRepository.findByEnabledTrueOrderByIdAsc();
    for (final CriticalDateRule rule : enabled) {
      rules.put(rule.getId(), rule);
    }
    final Set<String> open = retireStale(recordId, record, live, terms, rules);
    if (!live) {
      return;
    }
    final LocalDate today = LocalDate.now(ZoneId.of(props.getZone()));
    for (final CriticalDateRule rule : enabled) {
      final Optional<Watched> watched = watch(rule, terms);
      if (watched.isEmpty()) {
        continue;
      }
      final Watched w = watched.get();
      final String key = CriticalDateRules.sectionKey(rule.getId(), w.term.getId());
      if (open.contains(key)) {
        continue;
      }
      if (CriticalDateRules.decide(today, w.dueDate, rule.getLeadDays()) == Decision.CREATE) {
        createTask(record, rule, w, key);
        open.add(key);
      }
    }
  }

  /** Retires the open tasks of a record whose date no longer stands. Returns the keys still open. */
  private Set<String> retireStale(final Long recordId, final ContractRecord record,
      final boolean live, final Map<String, ContractTerm> terms,
      final Map<Long, CriticalDateRule> rules) {
    final Set<String> stillOpen = new HashSet<>();
    for (final CriticalDateTask task : taskRepository
        .findByContractRecord_IdAndStatusOrderByIdAsc(recordId, CriticalDateTask.OPEN)) {
      final String reason = retireReason(task, live, terms, rules);
      if (reason == null) {
        stillOpen.add(task.getSectionKey());
        continue;
      }
      task.setStatus(CriticalDateTask.RETIRED);
      task.setRetiredAt(LocalDateTime.now(ZoneOffset.UTC));
      task.setRetiredReason(reason);
      taskRepository.save(task);
      publisher.close(recordId, record == null ? null : record.getRecordCode(),
          task.getSectionKey(), "Closed by ELCM: " + reason, List.of());
      log.info("CRITICAL-DATES record {} task {} retired: {}", recordId, task.getSectionKey(),
          reason);
    }
    return stillOpen;
  }

  /** Why an open task no longer stands, or null when it does. */
  private String retireReason(final CriticalDateTask task, final boolean live,
      final Map<String, ContractTerm> terms, final Map<Long, CriticalDateRule> rules) {
    if (!live) {
      return "The contract record is no longer active";
    }
    final CriticalDateRule rule = rules.get(task.getRule().getId());
    if (rule == null) {
      return "The critical date rule was switched off";
    }
    final Optional<Watched> watched = watch(rule, terms);
    if (watched.isEmpty() || !watched.get().term.getId().equals(task.getContractTerm().getId())) {
      return "The date was replaced by a later Apply";
    }
    if (!watched.get().dueDate.equals(task.getDueDate())) {
      return "The due date changed to " + watched.get().dueDate;
    }
    return null;
  }

  /** The date a rule watches on a record: the term, its notice period and the due date. */
  private Optional<Watched> watch(final CriticalDateRule rule, final Map<String, ContractTerm> terms) {
    final ContractTerm term = terms.get(rule.getFieldKey());
    if (term == null || term.getValueDate() == null) {
      return Optional.empty();
    }
    Integer notice = null;
    if (rule.getNoticeFieldKey() != null && !rule.getNoticeFieldKey().isBlank()) {
      final ContractTerm noticeTerm = terms.get(rule.getNoticeFieldKey());
      if (noticeTerm == null || noticeTerm.getValueNumber() == null) {
        return Optional.empty();
      }
      notice = noticeTerm.getValueNumber().intValue();
    }
    return Optional.of(new Watched(term, notice,
        CriticalDateRules.dueDate(term.getValueDate(), notice)));
  }

  private void createTask(final ContractRecord record, final CriticalDateRule rule,
      final Watched w, final String key) {
    final String counterparty =
        record.getCounterparty() == null ? null : record.getCounterparty().getName();
    final Submission submission = w.term.getSourceSubmissionId() == null ? null
        : submissionRepository.findById(w.term.getSourceSubmissionId()).orElse(null);
    final ContractPackage pkg = submission == null ? null : submission.getContractPackage();
    final String packageAssignee = pkg == null ? null : loginOf(pkg.getAssigneeId());
    final String assignee = packageAssignee != null ? packageAssignee
        : picker.pick(ecwsProps.getApproverRole(), List.of()).orElse(null);

    final CriticalDateTask task = new CriticalDateTask();
    task.setRule(rule);
    task.setContractRecord(record);
    task.setContractTerm(w.term);
    task.setDueDate(w.dueDate);
    task.setSectionKey(key);
    task.setStatus(CriticalDateTask.OPEN);
    taskRepository.save(task);

    publisher.create(new CriticalDateRequest(record.getId(), record.getRecordCode(), key,
        CriticalDateRules.summary(rule.getLabel(), record.getRecordCode(), counterparty,
            w.dueDate),
        CriticalDateRules.description(rule.getLabel(), record.getRecordCode(), counterparty,
            w.term.getValueDate(), w.noticeDays, w.dueDate, null),
        rule.getPriority(), w.dueDate, assignee,
        requestor(submission, w.term), documentsText(),
        members(assignee, w.term.getAppliedBy(), submission == null ? null
            : submission.getSubmittedBy(), packageAssignee)));
    log.info("CRITICAL-DATES record {} task {} queued, due {}, assignee {}", record.getId(), key,
        w.dueDate, assignee);
  }

  /** The person who submitted the package that supplied the date, else who applied it. */
  static String requestor(final Submission submission, final ContractTerm term) {
    if (submission != null && submission.getSubmittedBy() != null
        && !submission.getSubmittedBy().isBlank()) {
      return submission.getSubmittedBy().trim();
    }
    return term.getAppliedBy() == null || term.getAppliedBy().isBlank() ? null
        : term.getAppliedBy().trim();
  }

  /** One "name TAB link" line, which ECWS shows as a clickable link; null when no base URL. */
  private String documentsText() {
    final String link = link();
    return link.isEmpty() ? null : "Contract Records in ELCM\t" + link;
  }

  private static boolean isLive(final ContractRecord record) {
    return record.getStatus() != null && record.getStatus().getCode() != null
        && LIVE_STATUS_CODES.contains(record.getStatus().getCode().toUpperCase(Locale.ROOT));
  }

  /** The login id for a stored package assignee (a login id, or on old rows a display name). */
  private String loginOf(final String assigneeId) {
    if (assigneeId == null || assigneeId.isBlank()) {
      return null;
    }
    return directory.findByAssigneeId(assigneeId).map(UserDirectoryDTO::getLoginId)
        .filter(login -> login != null && !login.isBlank()).orElse(null);
  }

  /** The people for the task's member list: no blanks, no "system", no repeats. */
  static List<String> members(final String... logins) {
    final List<String> result = new ArrayList<>();
    final Set<String> seen = new HashSet<>();
    for (final String login : logins) {
      if (login != null && !login.isBlank() && !SYSTEM.equalsIgnoreCase(login.trim())
          && seen.add(login.trim().toLowerCase(Locale.ROOT))) {
        result.add(login.trim());
      }
    }
    return result;
  }

  private String link() {
    String base = ecwsProps.getUiBaseUrl() == null ? "" : ecwsProps.getUiBaseUrl().trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base.isEmpty() ? "" : base + "/contractrecords";
  }

  /** A date being watched on one record. */
  private record Watched(ContractTerm term, Integer noticeDays, LocalDate dueDate) {
  }
}
