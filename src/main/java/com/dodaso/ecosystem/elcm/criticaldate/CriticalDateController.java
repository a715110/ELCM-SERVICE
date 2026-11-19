package com.dodaso.ecosystem.elcm.criticaldate;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADDED 2026-11-16 -- backs the Critical Dates screen (read only). Same placement and security
 * caveat as ContractRecordController.
 */
@RestController
@RequestMapping("/api/v1/pipeline/critical-dates")
@RequiredArgsConstructor
public class CriticalDateController {

  private final CriticalDateOverviewService overviewService;

  @GetMapping
  public CriticalDateOverview overview() {
    return overviewService.overview();
  }
}
