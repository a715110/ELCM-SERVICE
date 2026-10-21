package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import com.dodaso.ecosystem.elcm.activity.ActivityEvent;
import com.dodaso.ecosystem.elcm.activity.ActivityRecorder;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionWorker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Business logic for contract packages (FC-1 Pipeline), the public face used by the controller.
 *
 * REVISED 2026-08-19: backed by real repositories now.
 *
 * REVISED 2026-10-07 -- split in two, like StageDocumentService's two phases. The database work
 * lives in ContractPackageDataService (transactional, no outbound calls). This class calls it,
 * then does the two outbound lookups, the assignee's display name from IAMS and the file names
 * from common-service, outside any transaction. This class is deliberately not transactional.
 *
 * The package assignee is now a real column (contract_package.assignee_id, an IAMS login id),
 * replacing the earlier stand-in of created_by. It is shown as the resolved display name, falling
 * back to the stored value, or "Unassigned".
 *
 * "roles" (e.g. "1/1 roles") is a real computed value, not a stored column -- counts how many of
 * the package's documents have an actual role assigned (not UNDEFINED), matching the SDS 4.1
 * submission validation rule.
 */
@Service
@RequiredArgsConstructor
public class ContractPackageService {

    private static final String UNASSIGNED = "Unassigned";
    private static final String FILE_UNAVAILABLE = "(file unavailable)";

    private final ContractPackageDataService dataService;
    private final FileUploadLookupService fileUploadLookupService;
    private final AssigneeDirectoryLookupService assigneeDirectoryLookupService;
    private final ExtractionWorker extractionWorker;
    /** ADDED 2026-10-20: writes the package Activity Log (best effort, after the work is committed). */
    private final ActivityRecorder activityRecorder;

    public List<ContractPackageRow> findPackages() {
        final List<ContractPackageRow> rows = new ArrayList<>();
        for (final ContractPackageDraft d : dataService.loadPackages()) {
            final String assignee = displayNameOf(d.getAssigneeLoginId());
            rows.add(new ContractPackageRow(
                d.getId(),
                d.getPackageCode(),
                d.getDocCount(),
                d.getTargetRecord(),
                d.getWorkspace(),
                assignee,
                d.getAssigneeLoginId(),
                d.getRolesAssigned() + "/" + d.getDocCount() + " roles",
                d.getStatus(),
                d.getStatusCode(),
                (int) d.getRolesAssigned(),
                d.getExtractionState()));
        }
        return rows;
    }

    public List<PackageDocumentRow> findPackageDocuments(final Long packageId) {
        final List<PackageDocumentDraft> drafts = dataService.loadPackageDocuments(packageId);
        final Set<Long> fileUploadIds = new HashSet<>();
        for (final PackageDocumentDraft d : drafts) {
            if (d.getFileUploadId() != null) {
                fileUploadIds.add(d.getFileUploadId());
            }
        }
        final Map<Long, FileUploadDTO> files = fileUploadLookupService.findByIds(fileUploadIds);
        final List<PackageDocumentRow> rows = new ArrayList<>();
        for (final PackageDocumentDraft d : drafts) {
            final FileUploadDTO file = d.getFileUploadId() != null ? files.get(d.getFileUploadId()) : null;
            // Keep the row even when the file name cannot be resolved, so the document can still
            // be removed from the package.
            rows.add(new PackageDocumentRow(d.getStagedDocumentId(),
                file != null && file.getFileName() != null ? file.getFileName() : FILE_UNAVAILABLE,
                d.getRoleCode(), d.getRoleLabel(),
                d.getTargetRecord() != null ? d.getTargetRecord() : UNASSIGNED));
        }
        return rows;
    }

    public List<DocumentRoleOptionRow> findDocumentRoles() {
        return dataService.loadDocumentRoles();
    }

    public ContractPackageCreatedResponse createPackage(final CreateContractPackageRequest request) {
        final ContractPackageCreatedResponse created = dataService.createPackage(
            request != null ? request.getDocuments() : null,
            request != null ? request.getAssigneeId() : null);
        // ADDED 2026-10-20 -- Activity Log
        final int count = request != null && request.getDocuments() != null ? request.getDocuments().size() : 0;
        final String assigneeId = request != null ? request.getAssigneeId() : null;
        activityRecorder.recordLater(created.getId(), ActivityEvent.PACKAGE_CREATED,
            () -> "Package " + created.getPackageCode() + " was created with " + plural(count, "document")
                + (assigneeId != null && !assigneeId.isBlank()
                    ? ", assigned to " + displayNameOf(assigneeId) + "." : "."));
        return created;
    }

    public void addDocuments(final Long packageId, final AddPackageDocumentsRequest request) {
        dataService.addDocuments(packageId, request != null ? request.getDocuments() : null);
        // ADDED 2026-10-20 -- Activity Log
        final Set<Long> added = new HashSet<>();
        if (request != null && request.getDocuments() != null) {
            for (final DocumentRoleSelection s : request.getDocuments()) {
                added.add(s.getStagedDocumentId());
            }
        }
        activityRecorder.recordLater(packageId, ActivityEvent.DOCUMENTS_ADDED,
            () -> plural(added.size(), "document") + " added: " + namesOf(packageId, added) + ".");
    }

    public void removeDocument(final Long packageId, final Long stagedDocumentId) {
        // ADDED 2026-10-20 -- the link is gone after the removal, so the file id is read first
        // (database only; the file name is looked up later, off the request).
        Long fileUploadId = null;
        for (final PackageDocumentDraft d : dataService.loadPackageDocuments(packageId)) {
            if (stagedDocumentId != null && stagedDocumentId.equals(d.getStagedDocumentId())) {
                fileUploadId = d.getFileUploadId();
            }
        }
        dataService.removeDocument(packageId, stagedDocumentId);
        final Long removedFile = fileUploadId;
        activityRecorder.recordLater(packageId, ActivityEvent.DOCUMENT_REMOVED,
            () -> "Document " + fileNameOf(removedFile) + " was removed from the package.");
    }

    public void submit(final Long packageId, final String callerLoginId, final SubmitRequest request) {
        dataService.submit(packageId, callerLoginId, request != null ? request.correctionNote() : null);
        extractionWorker.wake(); // ADDED 2026-10-15 -- committed above, so the worker can start now
        // ADDED 2026-10-20 -- Activity Log
        final String note = request != null ? request.correctionNote() : null;
        activityRecorder.record(packageId, ActivityEvent.PACKAGE_SUBMITTED, callerLoginId,
            note != null && !note.isBlank()
                ? "Package was submitted again. Correction note: " + note.trim()
                : "Package was submitted for extraction.");
    }

    public void unsubmit(final Long packageId) {
        dataService.unsubmit(packageId);
        activityRecorder.record(packageId, ActivityEvent.PACKAGE_UNSUBMITTED,
            "The submission was withdrawn before extraction started.");
    }

    public void setDocumentRole(final Long packageId, final Long stagedDocumentId, final SetDocumentRoleRequest request) {
        dataService.setDocumentRole(packageId, stagedDocumentId, request != null ? request.roleCode() : null);
        // ADDED 2026-10-20 -- Activity Log
        final String roleCode = request != null ? request.roleCode() : null;
        activityRecorder.recordLater(packageId, ActivityEvent.DOCUMENT_ROLE_SET, () -> {
            final String role = roleCode == null || roleCode.isBlank() ? "Not defined yet"
                : findDocumentRoles().stream().filter(r -> roleCode.trim().equals(r.getCode()))
                    .map(DocumentRoleOptionRow::getLabel).findFirst().orElse(roleCode.trim());
            return "Role of " + namesOf(packageId, Set.of(stagedDocumentId)) + " was set to " + role + ".";
        });
    }

    public void reassign(final Long packageId, final ReassignPackageRequest request) {
        dataService.reassign(packageId, request != null ? request.getAssigneeId() : null);
        // ADDED 2026-10-20 -- Activity Log
        final String assigneeId = request != null ? request.getAssigneeId() : null;
        activityRecorder.recordLater(packageId, ActivityEvent.ASSIGNEE_CHANGED,
            () -> "Package was assigned to " + displayNameOf(assigneeId) + ".");
    }

    /** ADDED 2026-10-20 -- "3 documents", "1 document". */
    private static String plural(final int count, final String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /** ADDED 2026-10-20 -- file names of some of a package's documents, for Activity Log text. */
    public String namesOf(final Long packageId, final Set<Long> stagedDocumentIds) {
        final List<String> names = new ArrayList<>();
        for (final PackageDocumentRow row : findPackageDocuments(packageId)) {
            if (stagedDocumentIds.contains(row.getStagedDocumentId())) {
                names.add(row.getFileName());
            }
        }
        return names.isEmpty() ? "the document" : String.join(", ", names);
    }

    /** ADDED 2026-10-20 -- one file's name, or a neutral word when it cannot be found. */
    private String fileNameOf(final Long fileUploadId) {
        if (fileUploadId == null) {
            return "(unknown)";
        }
        final FileUploadDTO file = fileUploadLookupService.findByIds(Set.of(fileUploadId)).get(fileUploadId);
        return file != null && file.getFileName() != null ? file.getFileName() : FILE_UNAVAILABLE;
    }

    private String displayNameOf(final String loginId) {
        if (loginId == null || loginId.isBlank()) {
            return UNASSIGNED;
        }
        final Optional<UserDirectoryDTO> entry = assigneeDirectoryLookupService.findByAssigneeId(loginId);
        if (entry.isPresent() && entry.get().getDisplayName() != null && !entry.get().getDisplayName().isBlank()) {
            return entry.get().getDisplayName();
        }
        return loginId;
    }
}
