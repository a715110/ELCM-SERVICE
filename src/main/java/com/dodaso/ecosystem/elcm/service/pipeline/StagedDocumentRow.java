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
 *
 * ADDED 2026-10-03 -- recordCounterparty/recordContractType/recordStatus/
 * recordWorkspace, plus uploadedBy/comments, back the dashboard's new
 * hover-preview tooltips on the File Name and Record columns. Deliberately
 * flattened onto THIS row (rather than a nested ContractRecordDTO fetched
 * on hover) so hovering is instant and needs no extra request -- the four
 * record fields come from the SAME already-open Hibernate session as the
 * rest of this row's data (see StagedDocumentRepository.
 * findAllWithWorkspaceAndTargetRecord()'s now-wider JOIN FETCH), just like
 * workspace/record/assignee above. All four are null whenever
 * targetRecordId is null (nothing to preview -- see that field's own
 * note), and recordCounterparty specifically can be null even when a
 * record IS linked (ContractRecord.counterparty is itself nullable -- see
 * that entity's Javadoc).
 *
 * uploadedBy/comments were already columns on staged_document but were
 * never read into this row before now -- toDraft() previously only pulled
 * what the table's own columns needed. The File Name tooltip's exact field
 * set (Uploaded By / Uploaded At / Comments / File Type) is a reasonable
 * first cut, not something the user specified -- flag if a different set
 * is wanted.
 *
 * ADDED 2026-10-04 -- assigneeTeamName/assigneeRoles/assigneeWorkspaceCodes
 * back the dashboard's new Assignee-column hover preview, extending the
 * same File Name/Record tooltip feature per explicit request. Unlike the
 * record* fields above, these do NOT come from the same already-open
 * Hibernate session/JOIN FETCH -- "assignee" is just a free-text string on
 * staged_document (see class Javadoc: actually a display name, not a real
 * FK), so resolving it to team/role/workspace-specialty details requires a
 * separate outbound call to IAMS. See
 * AssigneeDirectoryLookupService/StageDocumentService.mapToRow() for that
 * lookup (cached, matched by display name, fails open to nulls here on any
 * miss/error). roles/workspaceCodes are comma-joined strings, not nested
 * lists -- this row is a flat table-display shape, same reasoning as the
 * record* fields being flat scalars rather than a nested DTO. All three
 * are null whenever the assignee didn't resolve to a directory entry
 * (unassigned, or a name with no directory match) -- the UI falls back to
 * an explanatory tooltip for that case, same pattern as the Record
 * column's "no linked record" variant.
 *
 * REVISED 2026-10-04 (assigneeId -> loginId) -- staged_document.assignee_id
 * now stores the assignee's IAMS login ID, so "assignee" above is no longer
 * the raw column value: it is the RESOLVED display name (falling back to the
 * raw stored value when unresolved, or "Unassigned"). assigneeLoginId, added
 * as the LAST field so the constructor order above stays untouched, is the
 * resolved person's login ID -- null exactly when the lookup did not
 * resolve, which makes it the reliable "did this resolve" flag for the UI
 * (assigneeRoles can't be: a resolved person may have no roles). Shown in the
 * tooltip so identically-named people can be told apart.
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
    private final String recordCounterparty;
    private final String recordContractType;
    private final String recordStatus;
    private final String recordWorkspace;
    private final String uploadedBy;
    private final String comments;
    private final String assigneeTeamName;
    private final String assigneeRoles;
    private final String assigneeWorkspaceCodes;
    private final String assigneeLoginId;
}
