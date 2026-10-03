package com.dodaso.ecosystem.elcm.service.pipeline;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import com.dodaso.ecosystem.elcm.container.StagedDocumentDTOContainer;
import com.dodaso.ecosystem.elcm.dto.StagedDocumentDTO;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpContractType;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpRoutingIntent;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpStagedDocumentStatus;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecord;
import com.dodaso.ecosystem.elcm.entity.pipeline.StagedDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.Workspace;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpContractTypeRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpRoutingIntentRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpStagedDocumentStatusRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.StagedDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.WorkspaceRepository;

import lombok.RequiredArgsConstructor;

/**
 * Business logic + data access for staged documents (FC-1 Pipeline).
 *
 * REVISED 2026-09-23: file_name/blob_uri no longer live on staged_document
 * (see the staged_document schema refactor -- common-service now owns file
 * storage, and staged_document holds only file_upload_id, a plain scalar
 * reference into elcm's cross-schema file_upload/common-service world).
 * Resolving file_upload_id -> file name/content-type therefore requires a
 * call out to common-service, which is what FileUploadLookupService does
 * (batching up to 20 ids per call, with its own cache).
 *
 * That outbound call is why this method is split into two phases:
 *
 *   1. loadStagedDocuments() -- @Transactional(readOnly = true). Reads every
 *      StagedDocument row and, while the Hibernate session is still open,
 *      eagerly extracts everything needed later (workspace code, target
 *      record code, assignee, uploadedAt, fileUploadId) into a plain
 *      in-memory holder (StagedDocumentDraft).
 *
 *   2. findStaged() -- NOT transactional. Calls phase 1, collects the full
 *      set of fileUploadIds from the drafts, makes ONE call to
 *      fileUploadLookupService.findByIds() (which internally chunks into
 *      batches of <=20 and checks its cache first), then builds the final
 *      StagedDocumentRow list. A DB transaction must never be held open
 *      across an outbound HTTP call to another service -- that would pin a
 *      pooled connection for the duration of the network round trip -- so
 *      this phase deliberately runs outside any @Transactional boundary.
 *
 * FIXED 2026-09-29: loadStagedDocuments() previously called
 * stagedDocumentRepository.findAll() and relied on its own
 * @Transactional(readOnly = true) to keep the Hibernate session open while
 * toDraft() read the LAZY workspace/targetRecord @ManyToOne associations.
 * That never actually worked: findStaged() calls loadStagedDocuments() via
 * self-invocation (`this.loadStagedDocuments(...)`, same class), which
 * bypasses Spring's transactional proxy entirely -- the exact same trap
 * already hit and fixed once in this codebase for ThumbnailStatusUpdater.
 * What actually ran the query was JpaRepository.findAll()'s OWN short-lived
 * transaction, already closed (spring.jpa.open-in-view=false) by the time
 * toDraft() touched doc.getWorkspace() -- so it came back null/uninitialized
 * instead of the real Workspace. Now calls
 * findAllWithWorkspaceAndTargetRecord() (JOIN FETCH on both associations --
 * see that repository method's Javadoc), so there is no lazy access left to
 * fail regardless of whether this method's own @Transactional actually
 * takes effect. The @Transactional annotation is left in place (harmless,
 * and correct practice), but the fix does not depend on it anymore.
 *
 * Per the confirmed batch-lookup requirements: any staged_document whose
 * fileUploadId does NOT resolve to an active common-service file_upload row
 * (never existed, soft-deleted, or the lookup call itself failed) is
 * excluded from the result entirely -- not shown with null/placeholder
 * metadata. This can only be decided once, after the batch call returns, so
 * it happens in findStaged(), not in the per-row mapping above it.
 *
 * Two DTO fields still don't correspond to a real staged_document column,
 * same as before the refactor:
 *   - "type" (PDF/DOC) -- now derived from the resolved FileUploadDTO's
 *     fileName (via the existing deriveFileType()), since that's the only
 *     place a file name is available post-refactor.
 *   - "record" -- shows the linked contract_record's record_code, or
 *     "Unassigned" when target_record_id is null.
 *   - "assignee" -- shows the raw assignee_id (an IAMS login_id/email) or
 *     "Unassigned".
 */
@Service
@RequiredArgsConstructor
public class StageDocumentService {
    private static final DateTimeFormatter UPLOADED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** See class Javadoc -- no contract-type input on the dialog yet. */
    private static final String DEFAULT_CONTRACT_TYPE_CODE = "PROPERTY_LEASE";

    /** See class Javadoc -- starting status for every upload-created row. */
    private static final String DEFAULT_STAGED_DOCUMENT_STATUS_CODE = "UPLOADING";

    private final StagedDocumentRepository stagedDocumentRepository;
    private final FileUploadLookupService fileUploadLookupService;
    private final WorkspaceRepository workspaceRepository;
    private final LkpContractTypeRepository lkpContractTypeRepository;
    private final LkpRoutingIntentRepository lkpRoutingIntentRepository;
    private final LkpStagedDocumentStatusRepository lkpStagedDocumentStatusRepository;
    private final RecordProvisioningService recordProvisioningService;

    /**
     * Not transactional on purpose -- see class Javadoc. Delegates the DB
     * read to loadStagedDocuments() (which IS transactional), then makes a
     * single batched/cached call out to common-service via
     * FileUploadLookupService before assembling the final rows.
     */
    public List<StagedDocumentRow> findStaged(String workspaceCode) {
        final List<StagedDocumentDraft> drafts = loadStagedDocuments(workspaceCode);

        final Set<Long> fileUploadIds = new HashSet<>();
        for (final StagedDocumentDraft draft : drafts) {
            if (draft.fileUploadId() != null) {
                fileUploadIds.add(draft.fileUploadId());
            }
        }

        final Map<Long, FileUploadDTO> resolved = fileUploadLookupService.findByIds(fileUploadIds);

        final List<StagedDocumentRow> rows = new ArrayList<>();
        for (final StagedDocumentDraft draft : drafts) {
            final FileUploadDTO fileUpload = draft.fileUploadId() != null ? resolved.get(draft.fileUploadId()) : null;
            if (fileUpload == null) {
                // id missing, soft-deleted, or common-service lookup failed --
                // exclude the row entirely rather than showing it with
                // null/placeholder file metadata (confirmed requirement).
                continue;
            }
            rows.add(mapToRow(draft, fileUpload));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    protected List<StagedDocumentDraft> loadStagedDocuments(String workspaceCode) {
        // workspaceCode filtering not implemented yet -- same as before,
        // parameter exists so callers already use the eventual real
        // signature. findAllWithWorkspaceAndTargetRecord() (not findAll())
        // until that filter is actually needed -- see repository method's
        // Javadoc and this class's 2026-09-29 fix note above for why plain
        // findAll() was the actual bug.
        return stagedDocumentRepository.findAllWithWorkspaceAndTargetRecord().stream()
            .map(this::toDraft)
            .toList();
    }

    private StagedDocumentDraft toDraft(StagedDocument doc) {
        String workspace = doc.getWorkspace().getCode();
        String record = deriveRecordLabel(doc);
        String assignee = doc.getAssigneeId() != null && !doc.getAssigneeId().isBlank()
            ? doc.getAssigneeId() : "Unassigned";
        String uploadedAt = doc.getUploadedAt() != null ? doc.getUploadedAt().format(UPLOADED_AT_FORMAT) : "";
        // ADDED 2026-10-01 -- see StagedDocumentRow's Javadoc: null here just
        // means "not yet linked to a record" (e.g. a NOT_SURE submission),
        // not an error -- findAllWithWorkspaceAndTargetRecord()'s JOIN FETCH
        // already makes this a safe, non-lazy read either way.
        final ContractRecord targetRecord = doc.getTargetRecord();
        Long targetRecordId = targetRecord != null ? targetRecord.getId() : null;

        // ADDED 2026-10-03 -- Record-column hover preview (see
        // StagedDocumentRow's Javadoc). Same "safe to read, no extra
        // query" reasoning as targetRecordId above -- the repository's JOIN
        // FETCH now covers targetRecord.counterparty/contractType/status/
        // workspace too. null targetRecord (nothing linked yet) or a null
        // counterparty (linked record just has none on file) both fall
        // through to null fields here; the UI decides what to show for
        // that, same as elsewhere (e.g. documentviewer.xhtml's dv-field
        // blocks).
        String recordCounterparty = targetRecord != null && targetRecord.getCounterparty() != null
            ? targetRecord.getCounterparty().getName() : null;
        String recordContractType = targetRecord != null && targetRecord.getContractType() != null
            ? targetRecord.getContractType().getLabel() : null;
        String recordStatus = targetRecord != null && targetRecord.getStatus() != null
            ? targetRecord.getStatus().getLabel() : null;
        String recordWorkspace = targetRecord != null && targetRecord.getWorkspace() != null
            ? targetRecord.getWorkspace().getName() : null;

        // ADDED 2026-10-03 -- File Name hover preview. Plain columns already
        // on this entity, just not read into a draft before now.
        String uploadedBy = doc.getUploadedBy();
        String comments = doc.getComments();

        return new StagedDocumentDraft(doc.getId(), doc.getFileUploadId(), workspace, record, assignee, uploadedAt,
            targetRecordId, recordCounterparty, recordContractType, recordStatus, recordWorkspace, uploadedBy,
            comments);
    }

    /**
     * ADDED 2026-09-29 -- targetRecord (a real ContractRecord row) is always
     * null right now: the Upload Files dialog has no record-matching flow
     * yet (see StagedDocument's Javadoc / StageDocumentService.toEntity()),
     * so the previous "record" logic here -- which only ever looked at
     * doc.getTargetRecord() -- showed "Unassigned" for every single row
     * regardless of what the user actually typed into the dialog's New
     * Record / Existing Record sub-panel, since that data lands in
     * newRecordName/newRecordCounterparty/existingRecordQuery instead, not
     * a linked ContractRecord.
     *
     * Priority: a real linked ContractRecord (once that flow exists) wins
     * if ever present; otherwise fall back to whichever destination's
     * fields are actually populated on this row -- New Record's name (or,
     * if the name was left blank since it's optional, its counterparty, so
     * the row isn't blank), then Existing Record's free-text search query,
     * then "Unassigned" only when none of the above have anything (e.g. a
     * genuine Not Sure submission, which has no record-identifying fields
     * at all by design).
     */
    private String deriveRecordLabel(StagedDocument doc) {
        if (doc.getTargetRecord() != null) {
            return doc.getTargetRecord().getRecordCode();
        }
        if (doc.getNewRecordName() != null && !doc.getNewRecordName().isBlank()) {
            return doc.getNewRecordName();
        }
        if (doc.getNewRecordCounterparty() != null && !doc.getNewRecordCounterparty().isBlank()) {
            return doc.getNewRecordCounterparty();
        }
        if (doc.getExistingRecordQuery() != null && !doc.getExistingRecordQuery().isBlank()) {
            return doc.getExistingRecordQuery();
        }
        return "Unassigned";
    }

    private StagedDocumentRow mapToRow(StagedDocumentDraft draft, FileUploadDTO fileUpload) {
        String fileName = fileUpload.getFileName();
        String type = deriveFileType(fileName);

        return new StagedDocumentRow(draft.id(), fileName, type, draft.workspace(), draft.record(), draft.assignee(),
            draft.uploadedAt(), draft.fileUploadId(), draft.targetRecordId(), draft.recordCounterparty(),
            draft.recordContractType(), draft.recordStatus(), draft.recordWorkspace(), draft.uploadedBy(),
            draft.comments());
    }

    private String deriveFileType(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1
            ? fileName.substring(dot + 1).toUpperCase()
            : "";
    }

    /**
     * The actual "Add to Pipeline" write path -- see class Javadoc for the
     * full root-cause/design explanation. One transaction for the whole
     * batch: either every file in this "Add to Pipeline" submission gets a
     * staged_document row (linked to the same resolved/created record), or
     * (on any lookup/creation failure) none do, rather than leaving a
     * partially-recorded submission behind.
     *
     * REVISED 2026-09-29: contractType now honors the submitted
     * contractTypeDTO's code when present (New Record's Contract Type
     * dropdown) rather than always using DEFAULT_CONTRACT_TYPE_CODE --
     * Existing Record and Not Sure submissions never set contractTypeDTO
     * (see UploadFilesService.submitToPipeline() on the elcm-ui side), so
     * they still fall back to the default.
     *
     * REVISED 2026-10-01: resolves (or creates) the real target
     * ContractRecord synchronously, per explicit decision in chat -- this
     * is no longer deferred to a later "Preparer promotes this" step.
     * routingIntent decides how:
     *   - NEW_RECORD: RecordProvisioningService.createNewRecord() creates a
     *     brand-new Counterparty(-or-reuse)/Address/Property/ContractRecord
     *     from the dialog's New Record sub-panel fields.
     *   - EXISTING_RECORD: RecordProvisioningService.findExistingRecord()
     *     looks up the record the user actually picked from the Existing
     *     Record autocomplete (first.getExistingRecordId()) -- NOT the raw
     *     search text, which is kept only as an audit trail (see
     *     StagedDocument.existingRecordQuery's Javadoc).
     *   - NOT_SURE (or anything else): targetRecord stays null, same as
     *     before -- a genuine "leave it in staging" submission has no
     *     record to resolve by design.
     * Resolved/created once per submission (same "same record for every
     * file in this batch" reasoning as workspace/contractType above), not
     * once per file.
     */
    @Transactional
    public StagedDocumentDTOContainer createStagedDocuments(final StagedDocumentDTOContainer requestContainer) {
        final List<StagedDocumentDTO> requested = requestContainer.getStagedDocumentDTOList();
        final StagedDocumentDTOContainer response = new StagedDocumentDTOContainer();
        if (requested == null || requested.isEmpty()) {
            response.setStagedDocumentDTOList(List.of());
            return response;
        }

        // Same workspace/destinationChoice/contractType for every entry in
        // one submission -- resolve once from the first entry. See class
        // Javadoc.
        final StagedDocumentDTO first = requested.get(0);
        final String workspaceCode = first.getWorkspaceDTO() != null ? first.getWorkspaceDTO().getCode() : null;
        final String routingIntentCode = first.getRoutingIntentDTO() != null ? first.getRoutingIntentDTO().getCode() : null;
        final String contractTypeCode = first.getContractTypeDTO() != null && first.getContractTypeDTO().getCode() != null
            ? first.getContractTypeDTO().getCode()
            : DEFAULT_CONTRACT_TYPE_CODE;

        final Workspace workspace = workspaceRepository.findByCode(workspaceCode)
            .orElseThrow(() -> new IllegalArgumentException("Unknown workspace code: " + workspaceCode));
        final LkpRoutingIntent routingIntent = lkpRoutingIntentRepository.findByCode(routingIntentCode)
            .orElseThrow(() -> new IllegalArgumentException("Unknown routing intent code: " + routingIntentCode));
        final LkpContractType contractType = lkpContractTypeRepository.findByCode(contractTypeCode)
            .orElseThrow(() -> new IllegalStateException(
                "Missing lkp_contract_type row for code: " + contractTypeCode));
        final LkpStagedDocumentStatus status = lkpStagedDocumentStatusRepository.findByCode(DEFAULT_STAGED_DOCUMENT_STATUS_CODE)
            .orElseThrow(() -> new IllegalStateException(
                "Missing default lkp_staged_document_status row for code: " + DEFAULT_STAGED_DOCUMENT_STATUS_CODE));

        final ContractRecord targetRecord = resolveTargetRecord(first, routingIntentCode, workspace, contractType);

        final LocalDateTime uploadedAt = LocalDateTime.now();

        final List<StagedDocument> toSave = requested.stream()
            .map(dto -> toEntity(dto, workspace, contractType, routingIntent, status, targetRecord, uploadedAt))
            .toList();

        final List<StagedDocument> saved = stagedDocumentRepository.saveAll(toSave);

        response.setStagedDocumentDTOList(saved.stream().map(this::toCreatedDto).toList());
        return response;
    }

    /**
     * See createStagedDocuments()'s own Javadoc for the three-way branch
     * this implements. Pulled out as its own method purely for readability
     * -- it's still called exactly once per submission, inside the same
     * @Transactional as the rest of createStagedDocuments().
     */
    private ContractRecord resolveTargetRecord(final StagedDocumentDTO first, final String routingIntentCode,
            final Workspace workspace, final LkpContractType contractType) {
        if (ROUTING_INTENT_NEW_RECORD.equals(routingIntentCode)) {
            return recordProvisioningService.createNewRecord(first, workspace, contractType);
        }
        if (ROUTING_INTENT_EXISTING_RECORD.equals(routingIntentCode)) {
            if (first.getExistingRecordId() == null) {
                throw new IllegalArgumentException(
                    "Existing Record was chosen but no record was actually selected (existingRecordId is null).");
            }
            return recordProvisioningService.findExistingRecord(first.getExistingRecordId());
        }
        // NOT_SURE, or any future routing intent this method doesn't know
        // about yet -- leave it unresolved rather than guessing.
        return null;
    }

    // elcm-service has no compile-time dependency on elcm-ui's
    // DestinationChoiceEnum (different module) -- routingIntentDTO.code is
    // just that enum's name() as a plain String on the wire (see
    // UploadFilesService.submitToPipeline() on the elcm-ui side). These
    // constants exist purely so resolveTargetRecord() above isn't comparing
    // against bare string literals; keep them in sync with
    // DestinationChoiceEnum's actual constant names if those ever change.
    private static final String ROUTING_INTENT_NEW_RECORD = "NEW_RECORD";
    private static final String ROUTING_INTENT_EXISTING_RECORD = "EXISTING_RECORD";

    private StagedDocument toEntity(StagedDocumentDTO dto, Workspace workspace, LkpContractType contractType,
        LkpRoutingIntent routingIntent, LkpStagedDocumentStatus status, ContractRecord targetRecord,
        LocalDateTime uploadedAt) {
        final StagedDocument doc = new StagedDocument();
        doc.setFileUploadId(dto.getFileUploadId());
        doc.setWorkspace(workspace);
        doc.setContractType(contractType);
        doc.setRoutingIntent(routingIntent);
        doc.setStatus(status);
        doc.setAssigneeId(dto.getAssigneeId());
        doc.setComments(dto.getComments());
        doc.setUploadedBy(dto.getUploadedBy());
        // See StagedDocument's Javadoc (REVISED 2026-10-01 note): these
        // stay as a durable audit trail of exactly what the user typed,
        // even though targetRecord (below) now also links the real record
        // synchronously.
        doc.setNewRecordName(dto.getNewRecordName());
        doc.setNewRecordCounterparty(dto.getNewRecordCounterparty());
        doc.setNewRecordPropertyAddress(dto.getNewRecordPropertyAddress());
        doc.setNewRecordAddressLine2(dto.getNewRecordAddressLine2());
        doc.setNewRecordCity(dto.getNewRecordCity());
        doc.setNewRecordState(dto.getNewRecordState());
        doc.setNewRecordZip(dto.getNewRecordZip());
        doc.setExistingRecordQuery(dto.getExistingRecordQuery());
        // ADDED 2026-10-01 -- previously always left null ("the dialog has
        // no way to pick a target contract_record yet"); now set to
        // whatever resolveTargetRecord() resolved/created for this
        // submission (null for a NOT_SURE submission, same as before).
        doc.setTargetRecord(targetRecord);
        // uploaded_at is a server fact, not client input -- see class
        // Javadoc.
        doc.setUploadedAt(uploadedAt);
        return doc;
    }

    /**
     * Minimal response mapping -- only the fields elcm-ui's UploadFilesBean
     * currently has any use for (id, fileUploadId). Nested workspace/
     * contractType/routingIntent/status DTOs are intentionally left unset;
     * add them if/when a caller actually needs the resolved lookup rows
     * echoed back.
     */
    private StagedDocumentDTO toCreatedDto(StagedDocument doc) {
        final StagedDocumentDTO dto = new StagedDocumentDTO();
        dto.setId(doc.getId());
        dto.setFileUploadId(doc.getFileUploadId());
        dto.setAssigneeId(doc.getAssigneeId());
        dto.setComments(doc.getComments());
        dto.setUploadedBy(doc.getUploadedBy());
        dto.setUploadedAt(doc.getUploadedAt());
        return dto;
    }

    /**
     * Everything mapToRow() needs from a StagedDocument entity, extracted
     * while the Hibernate session from loadStagedDocuments() is still open
     * -- see class Javadoc phase 1/2 split. Package-private record local to
     * this service; never returned outside it.
     *
     * REVISED 2026-10-03 -- recordCounterparty/recordContractType/
     * recordStatus/recordWorkspace/uploadedBy/comments added for the
     * dashboard's hover-preview tooltips -- see toDraft()'s and
     * StagedDocumentRow's own Javadoc.
     */
    private record StagedDocumentDraft(Long id, Long fileUploadId, String workspace, String record, String assignee,
                                       String uploadedAt, Long targetRecordId, String recordCounterparty,
                                       String recordContractType, String recordStatus, String recordWorkspace,
                                       String uploadedBy, String comments) {
    }

}