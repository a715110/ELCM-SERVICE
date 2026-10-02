package com.dodaso.ecosystem.elcm.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dodaso.ecosystem.elcm.service.pipeline.ContractRecordOptionRow;
import com.dodaso.ecosystem.elcm.service.pipeline.RecordProvisioningService;

import lombok.RequiredArgsConstructor;

/**
 * ADDED 2026-10-01 -- backs the Upload Files dialog's Existing Record
 * autocomplete (see RecordProvisioningService.search()'s Javadoc for match
 * rules/limitations). See PipelineMetricsController's class-level note on
 * package placement and the current lack of security, same caveat applies
 * here.
 */
@RestController
@RequestMapping("/api/v1/pipeline/contract-record")
@RequiredArgsConstructor
public class ContractRecordController {

    private final RecordProvisioningService recordProvisioningService;

    @GetMapping("search")
    public List<ContractRecordOptionRow> search(@RequestParam(required = false) String q) {
        return recordProvisioningService.search(q);
    }
}
