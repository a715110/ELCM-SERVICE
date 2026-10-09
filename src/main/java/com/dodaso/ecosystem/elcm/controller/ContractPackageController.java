package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.pipeline.AddPackageDocumentsRequest;
import com.dodaso.ecosystem.elcm.service.pipeline.ContractPackageCreatedResponse;
import com.dodaso.ecosystem.elcm.service.pipeline.ContractPackageRow;
import com.dodaso.ecosystem.elcm.service.pipeline.ContractPackageService;
import com.dodaso.ecosystem.elcm.service.pipeline.CreateContractPackageRequest;
import com.dodaso.ecosystem.elcm.service.pipeline.DocumentRoleOptionRow;
import com.dodaso.ecosystem.elcm.service.pipeline.PackageDocumentRow;
import com.dodaso.ecosystem.elcm.service.pipeline.ReassignPackageRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST access to ContractPackageService. See PipelineMetricsController's
 * class-level note on package placement.
 *
 * REVISED 2026-10-07 -- the write side: create a package from staged documents, add documents to
 * a draft package, remove one. Reads need pipeline:view, writes need pipeline:submit (any role
 * that may submit may group any document). Failures come back as status codes, see
 * ContractPackageDataService's class note: 400, 404, 409 and 422.
 */
@RestController
@RequestMapping("/api/v1/pipeline/contract-packages")
@RequiredArgsConstructor
public class ContractPackageController {

    private final ContractPackageService contractPackageService;
    private final CallerIdentityResolver callerIdentityResolver;

    @GetMapping
    @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
    public List<ContractPackageRow> getContractPackages() {
        return contractPackageService.findPackages();
    }

    /** Active document roles, for the dropdown in the Create Document Set dialog. */
    @GetMapping("/document-roles")
    @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
    public List<DocumentRoleOptionRow> getDocumentRoles() {
        return contractPackageService.findDocumentRoles();
    }

    /** The documents inside one package, with their roles. */
    @GetMapping("/{id}/documents")
    @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
    public List<PackageDocumentRow> getPackageDocuments(@PathVariable final Long id) {
        return contractPackageService.findPackageDocuments(id);
    }

    /** Creates a package, in ASSEMBLY, from the given staged documents. */
    @PostMapping
    @RequiresPermission(ElcmPermission.PIPELINE_SUBMIT)
    @ResponseStatus(HttpStatus.CREATED)
    public ContractPackageCreatedResponse createPackage(@RequestBody final CreateContractPackageRequest request) {
        return contractPackageService.createPackage(request);
    }

    /** Adds staged documents to an existing draft package. */
    @PostMapping("/{id}/documents")
    @RequiresPermission(ElcmPermission.PIPELINE_SUBMIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addDocuments(@PathVariable final Long id, @RequestBody final AddPackageDocumentsRequest request) {
        contractPackageService.addDocuments(id, request);
    }

    /** Changes the assignee of a draft package. */
    @PostMapping("/{id}/assignee")
    @RequiresPermission(ElcmPermission.PIPELINE_SUBMIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reassign(@PathVariable final Long id, @RequestBody final ReassignPackageRequest request) {
        contractPackageService.reassign(id, request);
    }

    /** Submit for extraction: 204; 400 not ready, 404 unknown, 409 not a draft. */
    @PostMapping("/{id}/submit")
    @RequiresPermission(ElcmPermission.PIPELINE_SUBMIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(@PathVariable final Long id, final HttpServletRequest httpRequest) {
        contractPackageService.submit(id, callerIdentityResolver.loginIdOf(httpRequest));
    }

    /** Unsubmit while the submission is still pending: 204; 404 unknown, 409 not submitted or picked up. */
    @PostMapping("/{id}/unsubmit")
    @RequiresPermission(ElcmPermission.PIPELINE_SUBMIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubmit(@PathVariable final Long id) {
        contractPackageService.unsubmit(id);
    }

    /** Removes one document from a draft package; it returns to the Stage Documents list. */
    @DeleteMapping("/{id}/documents/{stagedDocumentId}")
    @RequiresPermission(ElcmPermission.PIPELINE_SUBMIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDocument(@PathVariable final Long id, @PathVariable final Long stagedDocumentId) {
        contractPackageService.removeDocument(id, stagedDocumentId);
    }
}
