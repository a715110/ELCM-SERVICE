package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.contract.ContractRecordSummary;
import com.dodaso.ecosystem.elcm.service.contract.ContractRecordTerms;
import com.dodaso.ecosystem.elcm.service.contract.ContractTermReadService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract records with their applied terms (ADDED 2026-10-12), read only, pipeline:view.
 * GET /api/v1/pipeline/contract-records?q=      the list, with term counts
 * GET /api/v1/pipeline/contract-records/{id}/terms?history=false   one record's terms
 * (the existing /contract-record route, singular, is the autocomplete and is unchanged)
 */
@RestController
@RequestMapping("/api/v1/pipeline/contract-records")
@RequiredArgsConstructor
public class ContractTermController {

    private final ContractTermReadService contractTermReadService;

    @GetMapping
    @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
    public List<ContractRecordSummary> list(@RequestParam(required = false) final String q) {
        return contractTermReadService.list(q);
    }

    @GetMapping("/{id}/terms")
    @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
    public ContractRecordTerms terms(@PathVariable final Long id,
                                     @RequestParam(defaultValue = "false") final boolean history) {
        return contractTermReadService.terms(id, history);
    }
}
