package com.dodaso.ecosystem.elcm.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dodaso.ecosystem.elcm.container.StagedDocumentDTOContainer;
import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.pipeline.DeleteStagedDocumentRequest;
import com.dodaso.ecosystem.elcm.service.pipeline.StageDocumentService;
import com.dodaso.ecosystem.elcm.service.pipeline.StagedDocumentRow;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * REST access to StageDocumentService. See PipelineMetricsController's
 * class-level note on package placement and the current lack of security.
 */
@RestController
@RequestMapping("/api/v1/pipeline/staged-documents")
@RequiredArgsConstructor
public class StageDocumentController {

    private final StageDocumentService stageDocumentService;
    private final CallerIdentityResolver callerIdentityResolver;

    @GetMapping
    @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
    public List<StagedDocumentRow> getStagedDocuments(
            @RequestParam(required = false) String workspace) {
        // workspace filtering isn't implemented server-side yet -- see
        // StageDocumentService.findStaged()'s own note -- passed through
        // now so this endpoint's contract doesn't need to change once it is.
        return stageDocumentService.findStaged(workspace);
    }

    /**
     * ADDED 2026-09-23 -- the missing "Add to Pipeline" write endpoint.
     * elcm-ui's UploadFilesBean uploads files to common-service first (via
     * a separate call), then POSTs the resulting file_upload ids here, one
     * StagedDocumentDTO per file, to actually create the elcm.staged_document
     * rows. See StageDocumentService.createStagedDocuments()'s Javadoc for
     * the full root-cause explanation and the lookup-resolution rules.
     */
    @PostMapping
    @RequiresPermission(ElcmPermission.PIPELINE_SUBMIT)
    public StagedDocumentDTOContainer createStagedDocuments(
            @RequestBody final StagedDocumentDTOContainer requestContainer) {
        return stageDocumentService.createStagedDocuments(requestContainer);
    }

    /**
     * ADDED 2026-10-07 -- soft delete of one staged document, with an optional reason in the
     * body. POST on a /delete sub-path rather than HTTP DELETE because the reason is free text
     * that belongs in a body, and DELETE requests with a body are not reliably carried by the
     * HTTP client the UI calls through. Responses: 204 deleted; 404 not found or already
     * deleted; 403 not the uploader; 409 already in a package or submitted; 400 reason too long.
     * See StageDocumentService.deleteStaged() for the rules.
     */
    @PostMapping("/{id}/delete")
    @RequiresPermission(ElcmPermission.PIPELINE_DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStagedDocument(
            @PathVariable final Long id,
            @RequestBody(required = false) final DeleteStagedDocumentRequest request,
            final HttpServletRequest httpRequest) {
        stageDocumentService.deleteStaged(id,
            request != null ? request.getReason() : null,
            callerIdentityResolver.loginIdOf(httpRequest));
    }
}
