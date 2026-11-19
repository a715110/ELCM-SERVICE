package com.dodaso.ecosystem.elcm.criticaldate;

import com.dodaso.ecosystem.elcm.criticaldate.CriticalDateOverview.DateRow;
import com.dodaso.ecosystem.elcm.criticaldate.CriticalDateOverview.RuleRow;
import com.dodaso.ecosystem.elcm.entity.criticaldate.CriticalDateRule;
import com.dodaso.ecosystem.elcm.entity.criticaldate.CriticalDateTask;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecord;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractTerm;
import com.dodaso.ecosystem.elcm.integration.ecws.EcwsIntegrationProperties;
import com.dodaso.ecosystem.elcm.repository.criticaldate.CriticalDateRuleRepository;
import com.dodaso.ecosystem.elcm.repository.criticaldate.CriticalDateTaskRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractTermRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADDED 2026-11-16 -- read only view for the Critical Dates screen. It changes nothing: it shows
 * the rules and works out, for each live record and enabled rule, the date, the due date, the day
 * the task is made, and where the date stands. It uses the same date rules as CriticalDateService.
 */
@Service
@RequiredArgsConstructor
public class CriticalDateOverviewService {

  private static final Set<String> LIVE = Set.of("ACTIVE", "PENDING");

  private final CriticalDateProperties props;
  private final EcwsIntegrationProperties ecwsProps;
  private final CriticalDateRuleRepository ruleRepository;
  private final CriticalDateTaskRepository taskRepository;
  private final ContractTermRepository termRepository;

  @Transactional(readOnly = true)
  public CriticalDateOverview overview() {
    final LocalDate today = LocalDate.now(ZoneId.of(props.getZone()));
    final List<CriticalDateRule> all = ruleRepository.findAll();
    all.sort(Comparator.comparing(CriticalDateRule::getId));
    final List<RuleRow> ruleRows = new ArrayList<>();
    final Set<String> keys = new HashSet<>();
    final List<CriticalDateRule> enabled = new ArrayList<>();
    for (final CriticalDateRule rule : all) {
      ruleRows.add(new RuleRow(rule.getCode(), rule.getLabel(), rule.getFieldKey(),
          rule.getNoticeFieldKey(), rule.getLeadDays(), rule.getPriority(), rule.isEnabled()));
      if (rule.isEnabled()) {
        enabled.add(rule);
        keys.add(rule.getFieldKey());
        if (rule.getNoticeFieldKey() != null && !rule.getNoticeFieldKey().isBlank()) {
          keys.add(rule.getNoticeFieldKey());
        }
      }
    }
    final Set<String> openKeys = new HashSet<>();
    for (final CriticalDateTask task : taskRepository.findByStatus(CriticalDateTask.OPEN)) {
      openKeys.add(task.getSectionKey());
    }
    final List<DateRow> dates = new ArrayList<>();
    if (!keys.isEmpty()) {
      final Map<Long, Map<String, ContractTerm>> byRecord = new HashMap<>();
      for (final ContractTerm term : termRepository.findBySupersededAtIsNullAndFieldKeyIn(keys)) {
        byRecord.computeIfAbsent(term.getContractRecord().getId(), k -> new HashMap<>())
            .put(term.getFieldKey(), term);
      }
      for (final Map<String, ContractTerm> terms : byRecord.values()) {
        for (final CriticalDateRule rule : enabled) {
          rowFor(rule, terms, today, openKeys).ifPresent(dates::add);
        }
      }
    }
    dates.sort(Comparator.comparing(DateRow::dueDate).thenComparing(DateRow::recordCode,
        Comparator.nullsLast(Comparator.naturalOrder())));
    return new CriticalDateOverview(props.isEnabled(), ecwsProps.isEnabled(), props.getCron(),
        props.getZone(), today.toString(), ruleRows, dates);
  }

  private java.util.Optional<DateRow> rowFor(final CriticalDateRule rule,
      final Map<String, ContractTerm> terms, final LocalDate today, final Set<String> openKeys) {
    final ContractTerm term = terms.get(rule.getFieldKey());
    if (term == null || term.getValueDate() == null) {
      return java.util.Optional.empty();
    }
    final ContractRecord record = term.getContractRecord();
    if (record.getStatus() == null || record.getStatus().getCode() == null
        || !LIVE.contains(record.getStatus().getCode().toUpperCase(Locale.ROOT))) {
      return java.util.Optional.empty();
    }
    int notice = 0;
    if (rule.getNoticeFieldKey() != null && !rule.getNoticeFieldKey().isBlank()) {
      final ContractTerm noticeTerm = terms.get(rule.getNoticeFieldKey());
      if (noticeTerm == null || noticeTerm.getValueNumber() == null) {
        return java.util.Optional.empty();
      }
      notice = noticeTerm.getValueNumber().intValue();
    }
    final LocalDate due = CriticalDateRules.dueDate(term.getValueDate(), notice);
    final boolean hasTask =
        openKeys.contains(CriticalDateRules.sectionKey(rule.getId(), term.getId()));
    final String status =
        CriticalDateRules.status(today, due, rule.getLeadDays(), hasTask).name();
    final String counterparty =
        record.getCounterparty() == null ? "" : record.getCounterparty().getName();
    return java.util.Optional.of(new DateRow(record.getId(), record.getRecordCode(), counterparty,
        rule.getLabel(), term.getValueDate().toString(), due.toString(),
        CriticalDateRules.createOn(due, rule.getLeadDays()).toString(),
        ChronoUnit.DAYS.between(today, due), status, notice));
  }
}
