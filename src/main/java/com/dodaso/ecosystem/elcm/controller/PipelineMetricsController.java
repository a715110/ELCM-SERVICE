package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.service.pipeline.PipelineMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST access to PipelineMetricsService. Package deliberately
 * com.dodaso.ecosystem.elcm.controller (no ".ui" segment), per request --
 * note this differs from entity/repository/service/bean, which all live
 * under com.dodaso.ecosystem.elcm.ui.*. Still picked up by component
 * scanning either way, since ElcmUiApplication scans the whole
 * com.dodaso.ecosystem ancestor package.
 *
 * NO SECURITY on this endpoint yet -- spring-security hasn't been added to
 * the project (see pom.xml's MINIMUM-JARS list), so this is currently
 * unauthenticated/public once the app is reachable. Flagging plainly:
 * business data behind an open REST endpoint is a real gap, not something
 * to quietly leave for later without saying so.
 */
@RestController
@RequestMapping("/api/v1/pipeline/metrics")
@RequiredArgsConstructor
public class PipelineMetricsController {

    private final PipelineMetricsService pipelineMetricsService;

    @GetMapping
    public PipelineMetricsService.PipelineMetrics getMetrics() {
        return pipelineMetricsService.getMetrics();
    }
}
