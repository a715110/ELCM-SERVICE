package com.dodaso.ecosystem.elcm.integration.packagerail;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ADDED 2026-10-25 -- one line of the Package Rail. statusCode is ASSEMBLY, VALIDATED or COMPLETED
 * (statusLabel is the display text). relation is ASSIGNED, CREATED, SUBMITTER, REVIEWER or OTHER:
 * how the viewer is involved. updatedAt is an ISO date and time.
 */
@Getter
@Setter
@NoArgsConstructor
public class PackageRailItem {

  private Long packageId;
  private String packageCode;
  private String statusCode;
  private String statusLabel;
  private String workspace;
  private String assigneeLoginId;
  private String assignee;
  private int docCount;
  private String relation;
  private String updatedAt;
}
