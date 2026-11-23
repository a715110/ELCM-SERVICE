package com.dodaso.ecosystem.elcm.criticaldate;

import com.dodaso.ecosystem.elcm.criticaldate.CriticalDateRuleDtos.CheckResult;
import com.dodaso.ecosystem.elcm.criticaldate.CriticalDateRuleDtos.FieldOption;
import com.dodaso.ecosystem.elcm.criticaldate.CriticalDateRuleDtos.RuleAdminView;
import com.dodaso.ecosystem.elcm.criticaldate.CriticalDateRuleDtos.RuleRow;
import com.dodaso.ecosystem.elcm.criticaldate.CriticalDateRuleDtos.SaveRuleRequest;
import com.dodaso.ecosystem.elcm.entity.criticaldate.CriticalDateRule;
import com.dodaso.ecosystem.elcm.entity.criticaldate.CriticalDateTask;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionTemplateField;
import com.dodaso.ecosystem.elcm.integration.ecws.EcwsIntegrationProperties;
import com.dodaso.ecosystem.elcm.repository.criticaldate.CriticalDateRuleRepository;
import com.dodaso.ecosystem.elcm.repository.criticaldate.CriticalDateTaskRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionTemplateFieldRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADDED 2026-11-21 -- lets an administrator list, add, change, switch on or off and delete
 * critical date rules (table critical_date_rule) without a SQL script.
 *
 * <p>What a change does to tasks that already exist is decided by CriticalDateService at the next
 * check, not here: switching a rule off, or pointing it at another date, closes its open tasks in
 * ECWS; a new lead time, priority or name applies to tasks made from then on. A rule that has
 * ever made a task cannot be deleted (the history refers to it); it is switched off instead.
 *
 * <p>The write methods return nothing: the controller reads the screen again afterwards, so the
 * times written by the database (updated_at) are on the rows it returns.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CriticalDateRuleAdminService {

  private static final String PUBLISHED = "PUBLISHED";
  private static final String DATE = "DATE";
  private static final String NUMBER = "NUMBER";

  private final CriticalDateRuleRepository ruleRepository;
  private final CriticalDateTaskRepository taskRepository;
  private final ExtractionTemplateFieldRepository fieldRepository;
  private final CriticalDateService criticalDateService;
  private final CriticalDateProperties props;
  private final EcwsIntegrationProperties ecwsProps;

  /** The screen: every rule, the contract fields that can be picked, and the feature switches. */
  @Transactional(readOnly = true)
  public RuleAdminView view() {
    final Map<Long, Integer> open = openTaskCounts();
    final List<RuleRow> rows = new ArrayList<>();
    for (final CriticalDateRule rule : ruleRepository.findAllByOrderByIdAsc()) {
      rows.add(new RuleRow(rule.getId(), rule.getCode(), rule.getLabel(), rule.getFieldKey(),
          rule.getNoticeFieldKey(), rule.getLeadDays(), rule.getPriority(), rule.isEnabled(),
          open.getOrDefault(rule.getId(), 0), !taskRepository.existsByRule_Id(rule.getId()),
          rule.getUpdatedBy(), rule.getUpdatedAt() == null ? null : rule.getUpdatedAt().toString()));
    }
    return new RuleAdminView(props.isEnabled(), ecwsProps.isEnabled(), rows,
        options(DATE), options(NUMBER), CriticalDateRuleChecks.priorities());
  }

  @Transactional
  public void create(final SaveRuleRequest request, final String by) {
    final SaveRuleRequest body = requireBody(request);
    if (ruleRepository.count() >= CriticalDateRuleChecks.MAX_RULES) {
      throw conflict("There can be at most " + CriticalDateRuleChecks.MAX_RULES + " rules.");
    }
    final String code = CriticalDateRuleChecks.normalizeCode(body.code());
    require(CriticalDateRuleChecks.checkCode(code));
    if (ruleRepository.existsByCode(code)) {
      throw conflict("A rule with the code " + code + " already exists.");
    }
    final CriticalDateRule rule = new CriticalDateRule();
    rule.setCode(code);
    apply(rule, body, by);
    ruleRepository.save(rule);
    log.info("CRITICAL-DATES rule {} created by {}", code, by);
  }

  @Transactional
  public void update(final Long id, final SaveRuleRequest request, final String by) {
    final SaveRuleRequest body = requireBody(request);
    final CriticalDateRule rule = find(id);
    final String oldField = rule.getFieldKey();
    final String oldNotice = rule.getNoticeFieldKey();
    apply(rule, body, by);
    ruleRepository.save(rule);
    log.info("CRITICAL-DATES rule {} changed by {}{}", rule.getCode(), by,
        CriticalDateRuleChecks.changesWatchedDate(oldField, oldNotice, rule.getFieldKey(),
            rule.getNoticeFieldKey())
            ? " (the date it watches moved; open tasks close at the next check)" : "");
  }

  @Transactional
  public void setEnabled(final Long id, final boolean enabled, final String by) {
    final CriticalDateRule rule = find(id);
    rule.setEnabled(enabled);
    rule.setUpdatedBy(by);
    ruleRepository.save(rule);
    log.info("CRITICAL-DATES rule {} switched {} by {}", rule.getCode(), enabled ? "on" : "off",
        by);
  }

  @Transactional
  public void delete(final Long id, final String by) {
    final CriticalDateRule rule = find(id);
    if (taskRepository.existsByRule_Id(id)) {
      throw conflict("This rule has already made tasks, so it cannot be deleted. "
          + "Switch it off instead.");
    }
    ruleRepository.delete(rule);
    log.info("CRITICAL-DATES rule {} deleted by {}", rule.getCode(), by);
  }

  /**
   * Runs the check for every record now, so a changed rule does not wait for the morning. Nothing
   * runs when the critical dates or ECWS switch is off. Not one transaction: the check gives each
   * record its own.
   */
  public CheckResult checkNow(final String by) {
    if (!props.isEnabled() || !ecwsProps.isEnabled()) {
      return new CheckResult(false, 0, "Nothing was checked: Critical dates and ECWS tasks must "
          + "both be switched on in the service settings.");
    }
    final int checked = criticalDateService.checkAll();
    log.info("CRITICAL-DATES check run by {}, {} record(s) looked at", by, checked);
    return new CheckResult(true, checked, "Checked " + checked + " contract record(s).");
  }

  // ------------------------------------------------------------------ helpers

  /** Checks the request and copies it to the rule (not the code). */
  private void apply(final CriticalDateRule rule, final SaveRuleRequest body, final String by) {
    final String label = body.label() == null ? null : body.label().trim();
    final String fieldKey = CriticalDateRuleChecks.blankToNull(body.fieldKey());
    final String noticeKey = CriticalDateRuleChecks.blankToNull(body.noticeFieldKey());
    require(CriticalDateRuleChecks.checkLabel(label));
    require(CriticalDateRuleChecks.checkLeadDays(body.leadDays()));
    require(CriticalDateRuleChecks.checkPriority(body.priority()));
    require(CriticalDateRuleChecks.checkFields(fieldKey, noticeKey, keys(DATE), keys(NUMBER)));
    rule.setLabel(label);
    rule.setFieldKey(fieldKey);
    rule.setNoticeFieldKey(noticeKey);
    rule.setLeadDays(body.leadDays());
    rule.setPriority(CriticalDateRuleChecks.normalizePriority(body.priority()));
    rule.setEnabled(body.enabled());
    rule.setUpdatedBy(by);
  }

  private Map<Long, Integer> openTaskCounts() {
    final Map<Long, Integer> counts = new HashMap<>();
    for (final CriticalDateTask task : taskRepository.findByStatus(CriticalDateTask.OPEN)) {
      counts.merge(task.getRule().getId(), 1, Integer::sum);
    }
    return counts;
  }

  /** The fields of one data type in published templates, one entry per field key. */
  private List<FieldOption> options(final String dataType) {
    final Map<String, FieldOption> byKey = new LinkedHashMap<>();
    for (final ExtractionTemplateField field : fieldRepository
        .findByTemplate_StatusAndDataType_CodeOrderByFieldKeyAsc(PUBLISHED, dataType)) {
      byKey.putIfAbsent(field.getFieldKey(),
          new FieldOption(field.getFieldKey(), field.getLabel()));
    }
    return new ArrayList<>(byKey.values());
  }

  private Set<String> keys(final String dataType) {
    final Set<String> keys = new HashSet<>();
    for (final FieldOption option : options(dataType)) {
      keys.add(option.key());
    }
    return keys;
  }

  private CriticalDateRule find(final Long id) {
    return ruleRepository.findById(id).orElseThrow(
        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The rule was not found."));
  }

  private static SaveRuleRequest requireBody(final SaveRuleRequest request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Nothing to save.");
    }
    return request;
  }

  private static void require(final String message) {
    if (message != null) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
  }

  private static ResponseStatusException conflict(final String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
