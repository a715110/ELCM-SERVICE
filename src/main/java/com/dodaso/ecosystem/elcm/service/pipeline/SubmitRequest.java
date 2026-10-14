package com.dodaso.ecosystem.elcm.service.pipeline;

/**
 * Optional body of the submit call: what was corrected since the decline. Required (400 when blank)
 * when the package was submitted before.
 */
public record SubmitRequest(String correctionNote) {}
