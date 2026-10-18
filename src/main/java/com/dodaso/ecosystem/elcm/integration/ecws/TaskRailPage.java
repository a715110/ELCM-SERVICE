package com.dodaso.ecosystem.elcm.integration.ecws;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ADDED 2026-10-18 -- one page of Task Rail lines. nextBefore is the cursor to pass back to read
 * the next page of the Closed list; null means there is nothing more.
 */
@Getter
@Setter
@NoArgsConstructor
public class TaskRailPage {

  private List<TaskRailItem> items = new ArrayList<>();
  private Long nextBefore;
}
