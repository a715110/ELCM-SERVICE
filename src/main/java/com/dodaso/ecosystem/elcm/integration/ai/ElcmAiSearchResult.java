package com.dodaso.ecosystem.elcm.integration.ai;

import java.time.LocalDateTime;

/**
 * One AI search hit, with what ELCM-UI needs to open it in documentviewer.xhtml (fileUploadId,
 * fileName, recordId, record, workspace, type, assignee, uploadedAt) without another call per
 * result. File fields are null for a contract record hit.
 */
public record ElcmAiSearchResult(
    String sourceType,
    Long sourceId,
    String title,
    String snippet,
    Double score,
    Long workspaceId,
    String workspaceName,
    Long recordId,
    String recordCode,
    Long fileUploadId,
    String fileName,
    String contractType,
    String assignee,
    LocalDateTime uploadedAt) {
}
