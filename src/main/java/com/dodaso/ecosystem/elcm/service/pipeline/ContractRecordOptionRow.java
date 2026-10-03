package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One suggestion returned by ContractRecordController.search() for the
 * Upload Files dialog's Existing Record autocomplete
 * (uploadfilesdialog.xhtml's ufdExistingRecordSearch, bound to
 * UploadFilesBean.existingRecordId). Deliberately minimal (just enough to
 * display and identify a match) -- same "plain @Getter class, not a record"
 * reasoning as StagedDocumentRow/WorkspaceOptionRow: records' accessor
 * methods lack a "get" prefix, which classic EL property resolution can't
 * always find.
 *
 * elcm-ui has its own copy of this same shape
 * (com.dodaso.ecosystem.elcm.ui.service.pipeline.ContractRecordOptionRow)
 * for Jackson to deserialize the JSON this class serializes into -- same
 * two-unrelated-classes-same-name convention already used for
 * StagedDocumentRow, not a shared module type.
 *
 * ADDED 2026-10-02 -- counterpartyName, so a counterparty-matched result
 * shows why it matched (a bare record code gives no clue when the typed
 * text was "Acme", not "RETAIL-"). Null when the record has no counterparty
 * on file (ContractRecord.counterparty is nullable). See
 * RecordProvisioningService.search() for how this is populated.
 */
@Getter
@AllArgsConstructor
public class ContractRecordOptionRow implements Serializable {
    private final Long id;
    private final String recordCode;
    private final String counterpartyName;
}
