package com.dodaso.ecosystem.elcm.criticaldate;

import java.util.List;

/**
 * ADDED 2026-11-21 -- what the critical date rule screen sends and receives. Dates are ISO text.
 */
public final class CriticalDateRuleDtos {

  private CriticalDateRuleDtos() {
  }

  /** One rule with how many tasks it has open now. */
  public record RuleRow(Long id, String code, String label, String fieldKey, String noticeFieldKey,
      int leadDays, String priority, boolean enabled, int openTasks, boolean deletable,
      String updatedBy, String updatedAt) {
  }

  /** A contract field the rule may use, from the published templates. */
  public record FieldOption(String key, String label) {
  }

  /** The whole screen: rules, the pick lists and the two feature switches. */
  public record RuleAdminView(boolean enabled, boolean ecwsEnabled, List<RuleRow> rules,
      List<FieldOption> dateFields, List<FieldOption> numberFields, List<String> priorities) {
  }

  /** Create or change a rule. The code is used on create only; a rule keeps its code. */
  public record SaveRuleRequest(String code, String label, String fieldKey, String noticeFieldKey,
      Integer leadDays, String priority, boolean enabled) {
  }

  /** Switch a rule on or off. */
  public record EnabledRequest(boolean enabled) {
  }

  /** What "check now" did. active is false when a feature switch is off and nothing ran. */
  public record CheckResult(boolean active, int checked, String message) {
  }
}
