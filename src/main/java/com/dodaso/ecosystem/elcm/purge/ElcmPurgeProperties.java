package com.dodaso.ecosystem.elcm.purge;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ADDED 2026-10-21. Settings of the purge job for soft-deleted staged documents, bound from
 * {@code elcm.purge.*}.
 *
 * <p>The job is off ({@code enabled=false}) and, once on, only reports ({@code dry-run=true})
 * until it is switched to really delete. A purge cannot be undone.
 *
 * <p>{@code retention-days} is the default number of days a deleted document is kept after its
 * delete. {@code retention-days-by-workspace} overrides it per workspace code, so the policy can
 * differ by business area or customer. A value of 0 or less keeps that workspace's deleted
 * documents forever.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "elcm.purge")
public class ElcmPurgeProperties {

  /** Master switch of the nightly job. */
  private boolean enabled = false;

  /** When true the job only logs what it would remove. */
  private boolean dryRun = true;

  /** Default retention in days. */
  private int retentionDays = 90;

  /** Retention in days by workspace code, for example {@code retention-days-by-workspace[RE]=365}. */
  private Map<String, Integer> retentionDaysByWorkspace = new HashMap<>();

  /** Most documents handled per workspace in one run. */
  private int batchSize = 100;

  /** Retention in days for a workspace. Workspace codes are compared without case. */
  public int retentionFor(final String workspaceCode) {
    if (workspaceCode != null) {
      for (Map.Entry<String, Integer> entry : retentionDaysByWorkspace.entrySet()) {
        if (entry.getKey() != null
            && entry.getKey().trim().toLowerCase(Locale.ROOT)
                .equals(workspaceCode.trim().toLowerCase(Locale.ROOT))
            && entry.getValue() != null) {
          return entry.getValue();
        }
      }
    }
    return retentionDays;
  }
}
