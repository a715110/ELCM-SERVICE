package com.dodaso.ecosystem.elcm.integration.ecws;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ADDED 2026-10-18 -- one line of the Task Rail (the right-hand panel): a Review or Approval task
 * as ELCM knows it. The same field names are used by the elcm-ui copy (TaskRailRow), so the JSON
 * maps one to one.
 *
 * <p>event is REVIEW or APPROVAL. relation tells the viewer why they see it: ASSIGNED (it is
 * theirs), SUBMITTER (their package), REVIEWER (they reviewed it), POOL (nobody has it yet and
 * their role may take it) or WATCHING (admin view). dueState is OVERDUE, TODAY, LATER or NONE, and
 * only open tasks have one. closedAt and outcome are only set on closed tasks. ecwsTaskId (ADDED
 * 2026-10-18) is the id of the task in ECWS, for the link to its detail page; null until the task
 * has been sent to ECWS.
 */
@Getter
@Setter
@NoArgsConstructor
public class TaskRailItem {

  private Long taskId;
  private Long packageId;
  private Integer ecwsTaskId;
  private String packageCode;
  private String event;
  private String eventLabel;
  private String summary;
  private String assigneeLoginId;
  private String assignee;
  private String relation;
  private String dueDate;
  private String dueState;
  private String closedAt;
  private String outcome;
}
