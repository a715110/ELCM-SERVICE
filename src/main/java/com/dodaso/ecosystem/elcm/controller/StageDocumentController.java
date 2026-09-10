package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.service.pipeline.StageDocumentService;
import com.dodaso.ecosystem.elcm.service.pipeline.StagedDocumentRow;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        @RequestParam(required = false) String workspace
    ) {
        // workspace filtering isn't implemented server-side yet -- see
        // StageDocumentService.findStaged()'s own note -- passed through
        // now so this endpoint's contract doesn't need to change once it is.
        return stageDocumentService.findStaged(workspace);
    }
}
