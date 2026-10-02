package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Row shape returned by StageDocumentService. Field order below matches
 * exactly what StageDocumentService.mapToRow() passes into the
 * @AllArgsConstructor -- Lombok generates the constructor in field
 * declaration order, so that order has to stay in sync with the call site.
 *
 * Deliberately a plain @Getter class, NOT a record -- see
 * ContractPackageRow's equivalent note: records generate accessor methods
 * without a "get" prefix, which EL's classic property resolution can't
 * always find depending on the Faces/EL version on the classpath (this
 * matters for dashboard.xhtml's #{doc.fileName}-style bindings). A plain
 * class sidesteps the question regardless of which Faces version is
 * actually running.
 *
 * REVISED 2026-10-01 -- added id, fileUploadId, targetRecordId for the new
 * file-preview feature (the dashboard's eye icon): previously this row had
 * no identifier at all, so there was nothing for the UI to call a
 * preview/record-detail endpoint with. id/fileUploadId/targetRecordId are
 * deliberately plain scalars (not nested DTOs) -- this row is a lightweight
 * table-display shape, not a full entity graph; see
 * RecordProvisioningService.getRecordDetail() for where the full
 * ContractRecord detail (counterparty, address, etc.) actually gets
 * resolved, lazily, only once the user clicks the eye icon. targetRecordId
 * is null whenever StagedDocument.targetRecord is null (a NOT_SURE
 * submission, or any row created before the synchronous record-creation
 * feature existed) -- the UI's eye icon/record panel must treat null as
 * "not yet linked to a record", not as an error.
 */
@Getter
@AllArgsConstructor
public class StagedDocumentRow implements Serializable {
    private final Long id;
    private final String fileName;
    private final String type;
    private final String workspace;
    private final String record;
    private final String assignee;
    private final String uploadedAt;
    private final Long fileUploadId;
    private final Long targetRecordId;
}
