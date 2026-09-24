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
 *      in-memory holder (StagedDocumentDraft). workspace/targetRecord are
 *      lazy @ManyToOne relationships, and with spring.jpa.open-in-view=false
 *      (already set), those lazy loads must happen inside this transactional
 *      method or they'd throw LazyInitializationException later.
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
        // signature. findAll() until that filter is actually needed.
        return stagedDocumentRepository.findAll().stream()
            .map(this::toDraft)
            .toList();
    }

    private StagedDocumentDraft toDraft(StagedDocument doc) {
        String workspace = doc.getWorkspace().getCode();
        String record = doc.getTargetRecord() != null
            ? doc.getTargetRecord().getRecordCode()
            : "Unassigned";
        String assignee = doc.getAssigneeId() != null ? doc.getAssigneeId() : "Unassigned";
        String uploadedAt = doc.getUploadedAt() != null ? doc.getUploadedAt().format(UPLOADED_AT_FORMAT) : "";

        return new StagedDocumentDraft(doc.getFileUploadId(), workspace, record, assignee, uploadedAt);
    }

    private StagedDocumentRow mapToRow(StagedDocumentDraft draft, FileUploadDTO fileUpload) {
        String fileName = fileUpload.getFileName();
        String type = deriveFileType(fileName);

        return new StagedDocumentRow(fileName, type, draft.workspace(), draft.record(), draft.assignee(),
            draft.uploadedAt());
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
     * staged_document row, or (on any lookup failure) none do, rather than
     * leaving a partially-recorded submission behind.
     */
    @Transactional
    public StagedDocumentDTOContainer createStagedDocuments(final StagedDocumentDTOContainer requestContainer) {
        final List<StagedDocumentDTO> requested = requestContainer.getStagedDocumentDTOList();
        final StagedDocumentDTOContainer response = new StagedDocumentDTOContainer();
        if (requested == null || requested.isEmpty()) {
            response.setStagedDocumentDTOList(List.of());
            return response;
        }

        // Same workspace/destinationChoice for every entry in one
        // submission -- resolve once from the first entry. See class
        // Javadoc.
        final StagedDocumentDTO first = requested.get(0);
        final String workspaceCode = first.getWorkspaceDTO() != null ? first.getWorkspaceDTO().getCode() : null;
        final String routingIntentCode = first.getRoutingIntentDTO() != null ? first.getRoutingIntentDTO().getCode() : null;

        final Workspace workspace = workspaceRepository.findByCode(workspaceCode)
            .orElseThrow(() -> new IllegalArgumentException("Unknown workspace code: " + workspaceCode));
        final LkpRoutingIntent routingIntent = lkpRoutingIntentRepository.findByCode(routingIntentCode)
            .orElseThrow(() -> new IllegalArgumentException("Unknown routing intent code: " + routingIntentCode));
        final LkpContractType contractType = lkpContractTypeRepository.findByCode(DEFAULT_CONTRACT_TYPE_CODE)
            .orElseThrow(() -> new IllegalStateException(
                "Missing default lkp_contract_type row for code: " + DEFAULT_CONTRACT_TYPE_CODE));
        final LkpStagedDocumentStatus status = lkpStagedDocumentStatusRepository.findByCode(DEFAULT_STAGED_DOCUMENT_STATUS_CODE)
            .orElseThrow(() -> new IllegalStateException(
                "Missing default lkp_staged_document_status row for code: " + DEFAULT_STAGED_DOCUMENT_STATUS_CODE));

        final LocalDateTime uploadedAt = LocalDateTime.now();

        final List<StagedDocument> toSave = requested.stream()
            .map(dto -> toEntity(dto, workspace, contractType, routingIntent, status, uploadedAt))
            .toList();

        final List<StagedDocument> saved = stagedDocumentRepository.saveAll(toSave);

        response.setStagedDocumentDTOList(saved.stream().map(this::toCreatedDto).toList());
        return response;
    }

    private StagedDocument toEntity(StagedDocumentDTO dto, Workspace workspace, LkpContractType contractType,
            LkpRoutingIntent routingIntent, LkpStagedDocumentStatus status, LocalDateTime uploadedAt) {
        final StagedDocument doc = new StagedDocument();
        doc.setFileUploadId(dto.getFileUploadId());
        doc.setWorkspace(workspace);
        doc.setContractType(contractType);
        doc.setRoutingIntent(routingIntent);
        doc.setStatus(status);
        doc.setAssigneeId(dto.getAssigneeId());
        doc.setComments(dto.getComments());
        doc.setUploadedBy(dto.getUploadedBy());
        // uploaded_at is a server fact, not client input -- see class
        // Javadoc. target_record_id is left null: the dialog has no way to
        // pick a target contract_record yet (only applies once a real
        // record-matching flow exists for "Existing Record").
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
     */
    private record StagedDocumentDraft(Long fileUploadId, String workspace, String record, String assignee,
            String uploadedAt) {
    }

}
