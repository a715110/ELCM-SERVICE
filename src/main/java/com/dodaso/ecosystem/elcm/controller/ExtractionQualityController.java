package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.quality.ExtractionQualityReport;
import com.dodaso.ecosystem.elcm.service.quality.ExtractionQualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST access to the Extraction Quality report (ADDED 2026-10-16). Read only. Needs
 * TEMPLATE_MANAGE, the permission of the people who tune the templates, because that is who acts on
 * these numbers.
 */
@RestController
@RequestMapping("/api/v1/quality/extraction")
@RequiredArgsConstructor
public class ExtractionQualityController {

  private final ExtractionQualityService extractionQualityService;

  /**
   * The report for a date range and optional filters.
   *
   * @param from first day (yyyy-mm-dd), default 90 days ago
   * @param to last day (yyyy-mm-dd), default today
   * @param workspace workspace name, blank for all
   * @param template template name and version as listed in the options, blank for all
   * @param model model name, blank for all
   * @param granularity WEEK or MONTH, default WEEK
   */
  @GetMapping
  @RequiresPermission(ElcmPermission.TEMPLATE_MANAGE)
  public ExtractionQualityReport report(
      @RequestParam(name = "from", required = false) final String from,
      @RequestParam(name = "to", required = false) final String to,
      @RequestParam(name = "workspace", required = false) final String workspace,
      @RequestParam(name = "template", required = false) final String template,
      @RequestParam(name = "model", required = false) final String model,
      @RequestParam(name = "granularity", required = false) final String granularity) {
    return extractionQualityService.report(from, to, workspace, template, model, granularity);
  }
}
