package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.pipeline.WorkQueueItem;
import com.dodaso.ecosystem.elcm.service.pipeline.WorkQueueService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The work queue (ADDED 2026-10-14): every submitted or applied package with the state of its
 * newest submission, for the Work Queue screen. Read only, needs pipeline:view. The screen filters
 * by stage, assignee and workspace.
 */
@RestController
@RequestMapping("/api/v1/pipeline/work-queue")
@RequiredArgsConstructor
public class WorkQueueController {

  private final WorkQueueService workQueueService;

  @GetMapping
  @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
  public List<WorkQueueItem> getQueue() {
    return workQueueService.findQueue();
  }
}
