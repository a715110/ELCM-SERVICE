package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.elcm.entity.pipeline.StagedDocument;
import com.dodaso.ecosystem.elcm.repository.pipeline.StagedDocumentRepository;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic + data access for staged documents (FC-1 Pipeline).
 *
 * REVISED 2026-08-19: now backed by StagedDocumentRepository against the
 * real elcm.staged_document table, replacing the hardcoded mock rows this
 * class returned before. @Transactional(readOnly = true) matters here, not
 * just as a formality -- workspace/targetRecord are lazy @ManyToOne
 * relationships (see StagedDocument entity), and with
 * spring.jpa.open-in-view=false (already set), those lazy loads would throw
 * LazyInitializationException once the repository call returns unless the
 * whole method, including the entity-to-DTO mapping below, runs inside one
 * transaction.
 *
 * Three DTO fields from the original mock version don't correspond to any
 * real column, and are handled as noted inline in mapToRow() rather than
 * silently fabricated:
 *   - "type" (PDF/DOC) -- derived from the file_name extension, since
 *     staged_document has no dedicated file-type column.
 *   - "record" -- shows the linked contract_record's record_code, or
 *     "Unassigned" when target_record_id is null. The mock version's
 *     fabricated company-name suffix ("CR-2026-0039 - Globex LLC") is
 *     dropped -- there's no company/tenant name column anywhere on
 *     contract_record to source that from.
 *   - "assignee" -- shows the raw assignee_id (an IAMS login_id/email) or
 *     "Unassigned". The mock version's "Auto-routed" placeholder text isn't
 *     derivable from any real column and is dropped.
 */
@Service
@RequiredArgsConstructor
public class StageDocumentService {

    private static final DateTimeFormatter UPLOADED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final StagedDocumentRepository stagedDocumentRepository;

    @Transactional(readOnly = true)
    public List<StagedDocumentRow> findStaged(String workspaceCode) {
        // workspaceCode filtering not implemented yet -- same as before,
        // parameter exists so callers already use the eventual real
        // signature. findAll() until that filter is actually needed.
        return stagedDocumentRepository.findAll().stream()
            .map(this::mapToRow)
            .toList();
    }

    private StagedDocumentRow mapToRow(StagedDocument doc) {
        String type = deriveFileType(doc.getFileName());
        String workspace = doc.getWorkspace().getCode();
        String record = doc.getTargetRecord() != null
            ? doc.getTargetRecord().getRecordCode()
            : "Unassigned";
        String assignee = doc.getAssigneeId() != null ? doc.getAssigneeId() : "Unassigned";
        String uploadedAt = doc.getUploadedAt() != null ? doc.getUploadedAt().format(UPLOADED_AT_FORMAT) : "";

        return new StagedDocumentRow(doc.getFileName(), type, workspace, record, assignee, uploadedAt);
    }

    private String deriveFileType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1
            ? fileName.substring(dot + 1).toUpperCase()
            : "";
    }
}