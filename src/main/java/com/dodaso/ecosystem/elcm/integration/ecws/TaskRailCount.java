package com.dodaso.ecosystem.elcm.integration.ecws;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ADDED 2026-10-18 -- the Task Rail badge: open Review and Approval tasks assigned to the caller.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TaskRailCount {

  private int assignedToMe;
}
