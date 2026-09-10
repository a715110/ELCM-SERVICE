package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.service.pipeline.ContractPackageRow;
import com.dodaso.ecosystem.elcm.service.pipeline.ContractPackageService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST access to ContractPackageService. See PipelineMetricsController's
 * class-level note on package placement and the current lack of security.
 */
@RestController
@RequestMapping("/api/v1/pipeline/contract-packages")
@RequiredArgsConstructor
public class ContractPackageController {

    private final ContractPackageService contractPackageService;

    @GetMapping
    public List<ContractPackageRow> getContractPackages() {
        return contractPackageService.findPackages();
    }
}
