package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionResults;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionService;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Extraction progress and results of a submitted package (ADDED 2026-10-11). The extraction
 * itself runs in the background (ExtractionWorker); the screen polls /extraction for progress.
 * Reads need pipeline:view, Retry needs pipeline:submit.
 */
@RestController
@RequestMapping("/api/v1/pipeline/contract-packages/{id}/extraction")
@RequiredArgsConstructor
public class ExtractionController {

    private final ExtractionService extractionService;

    /** Progress: state, queue position, per-document status and elapsed time. */
    @GetMapping
    @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
    public ExtractionStatus status(@PathVariable final Long id) {
        return extractionService.status(id);
    }

    /** The extracted values with their evidence, per document. */
    @GetMapping("/results")
    @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
    public ExtractionResults results(@PathVariable final Long id) {
        return extractionService.results(id);
    }

    /** Puts a FAILED extraction back in the queue: 204; 404 no submission, 409 not failed. */
    @PostMapping("/retry")
    @RequiresPermission(ElcmPermission.PIPELINE_SUBMIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@PathVariable final Long id) {
        extractionService.retry(id);
    }
}
