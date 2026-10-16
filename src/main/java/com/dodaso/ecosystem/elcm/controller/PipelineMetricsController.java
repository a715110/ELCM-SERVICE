package com.dodaso.ecosystem.elcm.controller;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.pipeline.PipelineMetricsService;
import com.dodaso.ecosystem.elcm.service.pipeline.PipelineOverview;

import lombok.RequiredArgsConstructor;

/**
 * REST access to PipelineMetricsService.
 *
 * <p>REVISED 2026-10-16: returns the flat PipelineOverview (five cards plus the failed count)
 * instead of the four-count PipelineMetricsDTOContainer. The old DTO classes in the data model
 * are no longer used here and stay untouched.
 *
 * <p>ADDED 2026-10-16: optional repeated parameter login (the caller's login ids). When present,
 * the card numbers cover that person's own work only. It is a display filter over data the caller
 * can already see, so it grants nothing.
 */
@RestController
@RequestMapping("/api/v1/pipeline/metrics")
@RequiredArgsConstructor
public class PipelineMetricsController {

    private final PipelineMetricsService pipelineMetricsService;

    @GetMapping
    @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
    public PipelineOverview getMetrics(@RequestParam(name = "login", required = false) final List<String> login) {
        return pipelineMetricsService.getOverview(login);
    }
}
