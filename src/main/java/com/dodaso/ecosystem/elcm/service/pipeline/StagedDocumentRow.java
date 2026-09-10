package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Row shape returned by StageDocumentService. Field order below matches
 * exactly what StageDocumentService.mapToRow() passes into the
 * @AllArgsConstructor (fileName, type, workspace, record, assignee,
 * uploadedAt) -- Lombok generates the constructor in field declaration
 * order, so that order has to stay in sync with the call site.
 *
 * Deliberately a plain @Getter class, NOT a record -- see
 * ContractPackageRow's equivalent note: records generate accessor methods
 * without a "get" prefix, which EL's classic property resolution can't
 * always find depending on the Faces/EL version on the classpath (this
 * matters for dashboard.xhtml's #{doc.fileName}-style bindings). A plain
 * class sidesteps the question regardless of which Faces version is
 * actually running.
 */
@Getter
@AllArgsConstructor
public class StagedDocumentRow implements Serializable {
    private final String fileName;
    private final String type;
    private final String workspace;
    private final String record;
    private final String assignee;
    private final String uploadedAt;
}
