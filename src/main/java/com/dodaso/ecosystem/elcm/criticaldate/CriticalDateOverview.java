package com.dodaso.ecosystem.elcm.criticaldate;

import java.util.List;

/**
 * ADDED 2026-11-16 -- what the Critical Dates screen shows: the switches, the rules, and the dates
 * being watched. Dates are ISO text (yyyy-MM-dd); the screen formats them.
 */
public record CriticalDateOverview(boolean enabled, boolean ecwsEnabled, String cron, String zone,
    String today, List<RuleRow> rules, List<DateRow> dates) {

  /** One rule of critical_date_rule. */
  public record RuleRow(String code, String label, String fieldKey, String noticeFieldKey,
      int leadDays, String priority, boolean enabled) {
  }

  /** One date being watched on one live contract record. status is a CriticalDateRules.Status. */
  public record DateRow(Long recordId, String recordCode, String counterparty, String ruleLabel,
      String contractDate, String dueDate, String createOn, long daysLeft, String status,
      int noticeDays) {
  }
}
