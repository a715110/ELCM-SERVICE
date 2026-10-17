package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.elcm.entity.lookup.LkpDocumentRole;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpPackageStatus;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpStagedDocumentStatus;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpSubmissionStatus;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecord;
import com.dodaso.ecosystem.elcm.entity.pipeline.PackageDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.StagedDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.entity.pipeline.Workspace;
import com.dodaso.ecosystem.elcm.integration.ecws.EcwsTaskPublisher;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpDocumentRoleRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpPackageStatusRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpStagedDocumentStatusRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpSubmissionStatusRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractPackageRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.StagedDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import com.dodaso.ecosystem.elcm.service.extraction.ApplyStore;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionStore;
import com.dodaso.ecosystem.elcm.service.extraction.ReviewStore;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The database side of contract packages: every method here is one transaction, and nothing here
 * calls another service over the wire. ContractPackageService is the public face; it calls this
 * bean (a separate bean on purpose, so the transaction proxy applies, see the self-invocation
 * note in StageDocumentService) and then does the outbound lookups, for the assignee display
 * name and file names, outside any transaction.
 *
 * Rules, shared by create and add (the status codes the REST layer returns):
 *   400 no documents, a document listed twice, or an unknown role code.
 *   404 a document that does not exist or was deleted, or a missing package.
 *   409 a document already in a package or already submitted, or a package that is no longer a
 *       draft (status other than ASSEMBLY).
 *   422 documents from more than one workspace, or documents that do not match the package's
 *       workspace. One package, one workspace.
 * Any role with pipeline:submit may group any document (decided 2026-10-07); there is no
 * uploader check here, unlike delete.
 */
@Service
@RequiredArgsConstructor
public class ContractPackageDataService {

    static final String UNDEFINED_ROLE_CODE = "UNDEFINED";
    static final String ASSEMBLY_STATUS_CODE = "ASSEMBLY";
    static final String VALIDATED_STATUS_CODE = "VALIDATED";
    static final String COMPLETED_STATUS_CODE = "COMPLETED";
    static final int MAX_NOTE_LENGTH = 1000;
    static final String SUBMISSION_PENDING_CODE = "PENDING";
    private static final String STAGED_SUBMITTED_CODE = "SUBMITTED";
    /** The status every document starts in (StageDocumentService); Unsubmit puts documents back to it. */
    private static final String STAGED_DEFAULT_CODE = "UPLOADING";

    private final ContractPackageRepository contractPackageRepository;
    private final PackageDocumentRepository packageDocumentRepository;
    private final StagedDocumentRepository stagedDocumentRepository;
    private final LkpPackageStatusRepository lkpPackageStatusRepository;
    private final LkpDocumentRoleRepository lkpDocumentRoleRepository;
    private final SubmissionRepository submissionRepository;
    private final LkpSubmissionStatusRepository lkpSubmissionStatusRepository;
    private final LkpStagedDocumentStatusRepository lkpStagedDocumentStatusRepository;
    private final EntityManager entityManager;
    /** ADDED 2026-10-16: queues and closes ECWS tasks on submit and reassign. */
    private final EcwsTaskPublisher ecwsTaskPublisher;

    /**
     * REVISED 2026-10-15 -- three queries for the whole list, however many packages there are. It used
     * to run two count queries per package and then load each package's record, workspace and status
     * one by one (about 35 statements for 18 packages). Now: the packages with status, workspace and
     * target record joined in; the document and role counts of all packages in one grouped query; and
     * the status of each package's newest submission in one query (the stage the page shows after
     * Validated, so the page no longer asks the extraction status of every package one by one).
     */
    @Transactional(readOnly = true)
    public List<ContractPackageDraft> loadPackages() {
        final List<ContractPackage> packages = entityManager
            .createQuery("select p from ContractPackage p join fetch p.status join fetch p.workspace "
                + "left join fetch p.targetRecord order by p.id desc", ContractPackage.class)
            .getResultList();

        // package id -> {documents, documents with a role other than UNDEFINED}
        final Map<Long, long[]> counts = new HashMap<>();
        for (final Object[] row : entityManager
            .createQuery("select pd.contractPackage.id, count(pd), "
                + "sum(case when pd.documentRole.code <> :undefined then 1 else 0 end) "
                + "from PackageDocument pd group by pd.contractPackage.id", Object[].class)
            .setParameter("undefined", UNDEFINED_ROLE_CODE)
            .getResultList()) {
            counts.put((Long) row[0], new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }

        // package id -> status code of its newest submission
        final Map<Long, String> newestSubmission = new HashMap<>();
        for (final Object[] row : entityManager
            .createQuery("select s.contractPackage.id, s.status.code from Submission s "
                + "where s.id in (select max(s2.id) from Submission s2 group by s2.contractPackage.id)",
                Object[].class)
            .getResultList()) {
            newestSubmission.put((Long) row[0], (String) row[1]);
        }

        final List<ContractPackageDraft> drafts = new ArrayList<>();
        for (final ContractPackage pkg : packages) {
            final long[] c = counts.getOrDefault(pkg.getId(), new long[] {0, 0});
            final ContractRecord target = pkg.getTargetRecord();
            drafts.add(new ContractPackageDraft(
                pkg.getId(),
                pkg.getPackageCode(),
                (int) c[0],
                target != null ? target.getRecordCode() : "Unassigned",
                pkg.getWorkspace().getCode(),
                pkg.getAssigneeId(),
                c[1],
                pkg.getStatus().getLabel(),
                pkg.getStatus().getCode(),
                extractionStateOf(newestSubmission.get(pkg.getId()))));
        }
        return drafts;
    }

    /**
     * ADDED 2026-10-15 -- the extraction stage for a submission status code. Must give the same words
     * as the state in ExtractionReadService.status, which the page used to ask for each package.
     */
    static String extractionStateOf(final String submissionStatusCode) {
        if (submissionStatusCode == null) {
            return null;
        }
        return switch (submissionStatusCode) {
            case ExtractionStore.SUBMISSION_PENDING -> "QUEUED";
            case ExtractionStore.SUBMISSION_PROCESSING -> "RUNNING";
            case ExtractionStore.SUBMISSION_EXTRACTED -> "DONE";
            case ExtractionStore.SUBMISSION_FAILED -> "FAILED";
            case ReviewStore.SUBMISSION_DECLINED -> "DECLINED";
            case ReviewStore.SUBMISSION_REVIEWED -> "REVIEWED";
            case ApplyStore.SUBMISSION_APPLIED -> "APPLIED";
            default -> submissionStatusCode;
        };
    }

    @Transactional(readOnly = true)
    public List<PackageDocumentDraft> loadPackageDocuments(final Long packageId) {
        if (!contractPackageRepository.existsById(packageId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found");
        }
        final List<PackageDocumentDraft> drafts = new ArrayList<>();
        for (final PackageDocument pd : packageDocumentRepository.findDetailedByPackageId(packageId)) {
            drafts.add(new PackageDocumentDraft(
                pd.getStagedDocument().getId(),
                pd.getStagedDocument().getFileUploadId(),
                pd.getDocumentRole().getCode(),
                pd.getDocumentRole().getLabel(),
                pd.getStagedDocument().getTargetRecord() != null
                    ? pd.getStagedDocument().getTargetRecord().getRecordCode() : null));
        }
        return drafts;
    }

    @Transactional(readOnly = true)
    public List<DocumentRoleOptionRow> loadDocumentRoles() {
        final List<DocumentRoleOptionRow> rows = new ArrayList<>();
        for (final LkpDocumentRole role : lkpDocumentRoleRepository.findActiveOrdered()) {
            rows.add(new DocumentRoleOptionRow(role.getCode(), role.getLabel()));
        }
        return rows;
    }

    /** Creates a package in ASSEMBLY holding the given documents. See the class rules. */
    @Transactional
    public ContractPackageCreatedResponse createPackage(final List<DocumentRoleSelection> selections,
                                                        final String assigneeId) {
        final List<StagedDocument> documents = resolveDocuments(selections);
        final Workspace workspace = singleWorkspace(documents, null);
        final Map<String, LkpDocumentRole> roles = resolveRoles(selections);

        final LkpPackageStatus assembly = lkpPackageStatusRepository.findByCode(ASSEMBLY_STATUS_CODE)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Package status " + ASSEMBLY_STATUS_CODE + " is not configured"));

        final ContractPackage pkg = new ContractPackage();
        pkg.setWorkspace(workspace);
        pkg.setStatus(assembly);
        pkg.setTargetRecord(sharedTargetRecord(documents));
        pkg.setAssigneeId(blankToNull(assigneeId));
        // The code needs the generated id, so save once with a throwaway code and then set the
        // real one. Both happen in this transaction; nothing else ever sees the throwaway.
        pkg.setPackageCode("PKG-NEW-" + UUID.randomUUID().toString().substring(0, 8));
        final ContractPackage saved = contractPackageRepository.save(pkg);
        saved.setPackageCode(String.format("PKG-%d-%03d", Year.now(ZoneOffset.UTC).getValue(), saved.getId()));

        attach(saved, documents, selections, roles);
        return new ContractPackageCreatedResponse(saved.getId(), saved.getPackageCode());
    }

    /** Adds documents to a draft package; they must be in the package's workspace. */
    @Transactional
    public void addDocuments(final Long packageId, final List<DocumentRoleSelection> selections) {
        final ContractPackage pkg = loadDraftPackage(packageId);
        final List<StagedDocument> documents = resolveDocuments(selections);
        singleWorkspace(documents, pkg.getWorkspace());
        final Map<String, LkpDocumentRole> roles = resolveRoles(selections);
        attach(pkg, documents, selections, roles);
    }

    /**
     * Takes one document out of a draft package. The document goes back to the Stage Documents
     * list. An emptied package is kept (it can still be added to); it just cannot be submitted.
     */
    @Transactional
    public void removeDocument(final Long packageId, final Long stagedDocumentId) {
        loadDraftPackage(packageId);
        final PackageDocument link = packageDocumentRepository
            .findByContractPackage_IdAndStagedDocument_Id(packageId, stagedDocumentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document is not in this package"));
        packageDocumentRepository.delete(link);
    }

    /**
     * Changes the assignee of a draft package. 400 for a blank assignee, 404 for an unknown
     * package, 409 when the package is no longer a draft.
     */
    @Transactional
    public void reassign(final Long packageId, final String assigneeId) {
        final String assignee = blankToNull(assigneeId);
        if (assignee == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assignee is required");
        }
        final ContractPackage pkg = loadDraftPackage(packageId);
        pkg.setAssigneeId(assignee);
        contractPackageRepository.save(pkg);
        // ADDED 2026-10-16: an open fix task moves to the new assignee.
        ecwsTaskPublisher.reassigned(packageId, assignee);
    }

    /**
     * Submit for extraction (decided 2026-10-08). The package moves ASSEMBLY to VALIDATED, a
     * submission row is created as PENDING, and every document becomes SUBMITTED. Ready means: at
     * least one document and an assignee. A document may still be UNDEFINED (changed 2026-10-14):
     * the role is set later with setDocumentRole, and Complete review requires every role to be set.
     * The target record is not required. 404 unknown package, 409 not a draft, 400 not ready.
     * A package submitted before (its earlier submission was declined) needs a correction note, up to
     * 1000 characters, kept on the new submission (400 otherwise). Nothing else changes while the package is VALIDATED: reassign, add and remove all answer 409.
     */
    @Transactional
    public void submit(final Long packageId, final String callerLoginId, final String correctionNote) {
        final ContractPackage pkg = loadDraftPackage(packageId);
        final List<PackageDocument> links = packageDocumentRepository.findDetailedByPackageId(packageId);
        if (links.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The package has no documents");
        }
        if (blankToNull(pkg.getAssigneeId()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The package has no assignee");
        }
        // A package that was submitted before has a declined submission on record: this is a
        // resubmission and needs a note on what was corrected (ADDED 2026-10-14).
        final String note = blankToNull(correctionNote);
        if (!submissionRepository.findByContractPackage_Id(packageId).isEmpty()) {
            if (note == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A correction note is required");
            }
            if (note.length() > MAX_NOTE_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The correction note is longer than " + MAX_NOTE_LENGTH + " characters");
            }
        }
        final LkpPackageStatus validated = lkpPackageStatusRepository.findByCode(VALIDATED_STATUS_CODE)
            .orElseThrow(() -> seedMissing("lkp_package_status", VALIDATED_STATUS_CODE));
        final LkpSubmissionStatus pending = lkpSubmissionStatusRepository.findByCode(SUBMISSION_PENDING_CODE)
            .orElseThrow(() -> seedMissing("lkp_submission_status", SUBMISSION_PENDING_CODE));
        final LkpStagedDocumentStatus submittedDoc = lkpStagedDocumentStatusRepository.findByCode(STAGED_SUBMITTED_CODE)
            .orElseThrow(() -> seedMissing("lkp_staged_document_status", STAGED_SUBMITTED_CODE));

        pkg.setStatus(validated);
        contractPackageRepository.save(pkg);

        final Submission submission = new Submission();
        submission.setContractPackage(pkg);
        submission.setStatus(pending);
        submission.setSubmittedBy(callerLoginId != null && !callerLoginId.isBlank() ? callerLoginId : "system");
        submission.setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC));
        submission.setCorrectionNote(note);
        submissionRepository.save(submission);

        for (final PackageDocument link : links) {
            link.getStagedDocument().setStatus(submittedDoc);
            stagedDocumentRepository.save(link.getStagedDocument());
        }
        // ADDED 2026-10-16: the fix task is done once the package is submitted again.
        ecwsTaskPublisher.resubmitted(packageId, submission.getSubmittedBy());
    }

    /**
     * Unsubmit, allowed only while nobody has picked the submission up (the newest submission of
     * the package is still PENDING). That submission row is deleted, the documents go back to their
     * starting status, and the package returns to ASSEMBLY. 404 unknown package, 409 not
     * submitted or already picked up.
     */
    @Transactional
    public void unsubmit(final Long packageId) {
        final ContractPackage pkg = contractPackageRepository.findById(packageId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
        if (!VALIDATED_STATUS_CODE.equals(pkg.getStatus().getCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Package is not submitted");
        }
        // Only the newest submission counts: older ones are history (a declined submission stays
        // on record after the package returns to Assembly and is submitted again).
        final Submission newest = submissionRepository.findByContractPackage_Id(packageId).stream()
            .max(java.util.Comparator.comparing(Submission::getId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Package is not submitted"));
        if (!SUBMISSION_PENDING_CODE.equals(newest.getStatus().getCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The submission has already been picked up");
        }
        final LkpPackageStatus assembly = lkpPackageStatusRepository.findByCode(ASSEMBLY_STATUS_CODE)
            .orElseThrow(() -> seedMissing("lkp_package_status", ASSEMBLY_STATUS_CODE));
        final LkpStagedDocumentStatus startStatus = lkpStagedDocumentStatusRepository.findByCode(STAGED_DEFAULT_CODE)
            .orElseThrow(() -> seedMissing("lkp_staged_document_status", STAGED_DEFAULT_CODE));
        submissionRepository.delete(newest);
        for (final PackageDocument link : packageDocumentRepository.findDetailedByPackageId(packageId)) {
            link.getStagedDocument().setStatus(startStatus);
            stagedDocumentRepository.save(link.getStagedDocument());
        }
        pkg.setStatus(assembly);
        contractPackageRepository.save(pkg);
    }

    /**
     * Sets the role of one document in a package (ADDED 2026-10-14); a blank code sets it back to
     * UNDEFINED. Allowed while the package is a draft or submitted, not after it is completed
     * (applied). 404 unknown package or a document that is not in it; 400 unknown role; 409 the
     * package is completed.
     */
    @Transactional
    public void setDocumentRole(final Long packageId, final Long stagedDocumentId, final String roleCode) {
        final ContractPackage pkg = contractPackageRepository.findById(packageId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
        if (COMPLETED_STATUS_CODE.equals(pkg.getStatus().getCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The package is completed");
        }
        final PackageDocument link = packageDocumentRepository
            .findByContractPackage_IdAndStagedDocument_Id(packageId, stagedDocumentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document is not in this package"));
        final String code = roleCode == null || roleCode.isBlank() ? UNDEFINED_ROLE_CODE : roleCode.trim();
        final LkpDocumentRole role = lkpDocumentRoleRepository.findByCode(code)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown document role: " + code));
        link.setDocumentRole(role);
        packageDocumentRepository.save(link);
    }

    private static ResponseStatusException seedMissing(final String table, final String code) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Missing " + table + " row: " + code);
    }

    private ContractPackage loadDraftPackage(final Long packageId) {
        final ContractPackage pkg = contractPackageRepository.findById(packageId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
        if (!ASSEMBLY_STATUS_CODE.equals(pkg.getStatus().getCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Package is no longer a draft");
        }
        return pkg;
    }

    /** Validates the selection and loads the documents, in the order given. */
    private List<StagedDocument> resolveDocuments(final List<DocumentRoleSelection> selections) {
        if (selections == null || selections.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No documents selected");
        }
        final Set<Long> ids = new HashSet<>();
        for (final DocumentRoleSelection s : selections) {
            if (s == null || s.getStagedDocumentId() == null || !ids.add(s.getStagedDocumentId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or repeated document in selection");
            }
        }
        final Map<Long, StagedDocument> byId = new HashMap<>();
        for (final StagedDocument d : stagedDocumentRepository.findAllById(ids)) {
            byId.put(d.getId(), d);
        }
        final List<StagedDocument> ordered = new ArrayList<>();
        for (final DocumentRoleSelection s : selections) {
            final StagedDocument d = byId.get(s.getStagedDocumentId());
            if (d == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found or deleted");
            }
            ordered.add(d);
        }
        if (!packageDocumentRepository.findByStagedDocument_IdIn(ids).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A document is already in a package");
        }
        for (final StagedDocument d : ordered) {
            if (d.getStatus() != null && STAGED_SUBMITTED_CODE.equals(d.getStatus().getCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A document has already been submitted");
            }
        }
        return ordered;
    }

    /** All documents must share one workspace, and match the package's when one is given. */
    private Workspace singleWorkspace(final List<StagedDocument> documents, final Workspace required) {
        final Workspace first = required != null ? required : documents.get(0).getWorkspace();
        for (final StagedDocument d : documents) {
            if (!first.getId().equals(d.getWorkspace().getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "All documents in a package must be in the same workspace");
            }
        }
        return first;
    }

    /** Role code per selection, resolved once per distinct code; blank means UNDEFINED. */
    private Map<String, LkpDocumentRole> resolveRoles(final List<DocumentRoleSelection> selections) {
        final Map<String, LkpDocumentRole> roles = new HashMap<>();
        for (final DocumentRoleSelection s : selections) {
            final String code = effectiveRoleCode(s);
            if (!roles.containsKey(code)) {
                roles.put(code, lkpDocumentRoleRepository.findByCode(code)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown document role: " + code)));
            }
        }
        return roles;
    }

    private static String effectiveRoleCode(final DocumentRoleSelection s) {
        return s.getRoleCode() == null || s.getRoleCode().isBlank() ? UNDEFINED_ROLE_CODE : s.getRoleCode().trim();
    }

    private void attach(final ContractPackage pkg, final List<StagedDocument> documents,
                        final List<DocumentRoleSelection> selections, final Map<String, LkpDocumentRole> roles) {
        for (int i = 0; i < documents.size(); i++) {
            final PackageDocument link = new PackageDocument();
            link.setContractPackage(pkg);
            link.setStagedDocument(documents.get(i));
            link.setDocumentRole(roles.get(effectiveRoleCode(selections.get(i))));
            packageDocumentRepository.save(link);
        }
    }

    /** The record every selected document is already linked to, or null when they differ or have none. */
    private static ContractRecord sharedTargetRecord(final List<StagedDocument> documents) {
        ContractRecord shared = null;
        for (final StagedDocument d : documents) {
            final ContractRecord record = d.getTargetRecord();
            if (record == null) {
                return null;
            }
            if (shared == null) {
                shared = record;
            } else if (!shared.getId().equals(record.getId())) {
                return null;
            }
        }
        return shared;
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
