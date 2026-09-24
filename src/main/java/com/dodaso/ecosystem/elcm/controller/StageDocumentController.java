package com.dodaso.ecosystem.elcm.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dodaso.ecosystem.elcm.container.StagedDocumentDTOContainer;
import com.dodaso.ecosystem.elcm.service.pipeline.StageDocumentService;
import com.dodaso.ecosystem.elcm.service.pipeline.StagedDocumentRow;

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

    @GetMapping
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
    public StagedDocumentDTOContainer createStagedDocuments(
            @RequestBody final StagedDocumentDTOContainer requestContainer) {
        return stageDocumentService.createStagedDocuments(requestContainer);
    }
}
