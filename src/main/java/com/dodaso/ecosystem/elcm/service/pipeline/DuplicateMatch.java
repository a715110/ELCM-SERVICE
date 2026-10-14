package com.dodaso.ecosystem.elcm.service.pipeline;

/**
 * An earlier upload with the same content. packageCode is null while the document is still only
 * staged. uploadedAt is ISO text (UTC).
 */
public record DuplicateMatch(
    String hash,
    Long stagedDocumentId,
    String fileName,
    String uploadedBy,
    String uploadedAt,
    String packageCode) {}
