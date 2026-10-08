package com.dodaso.ecosystem.elcm.service.pipeline;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** A package member read inside a transaction; the file name is resolved afterwards from common-service. */
@Getter
@AllArgsConstructor
public class PackageDocumentDraft {
    private final Long stagedDocumentId;
    private final Long fileUploadId;
    private final String roleCode;
    private final String roleLabel;
    /** Code of the record this document is linked to, or null when it has none. */
    private final String targetRecord;
}
