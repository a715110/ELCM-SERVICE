package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionFieldResult;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionTemplateField;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecord;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractTerm;
import com.dodaso.ecosystem.elcm.entity.pipeline.Counterparty;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.integration.ecws.EcwsTaskPublisher;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionFieldRevisionRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpPackageStatusRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpSubmissionStatusRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractPackageRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractRecordRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractTermRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.CounterpartyRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Apply to contract (ADDED 2026-10-12): writes the reviewed values of a package to its target
 * contract record. One transaction.
 * <p>
 * Rules (status codes the REST layer returns): 404 the package has no submission. 409 the newest
 * submission is not REVIEWED (not reviewed yet, declined, or already APPLIED). 403 the caller
 * submitted the package or completed its review (see ReviewerSeparation.checkApprover). 422 the
 * package has no target contract record and none was chosen (targetRecordId), or the chosen record
 * is not in the package's workspace or is not ACTIVE or PENDING. Nothing is created. A package that
 * has a target ignores targetRecordId. What is written: - Only fields the reviewer confirmed
 * (verified_flag) are written. The value is the corrected value when the reviewer entered one,
 * otherwise the extracted value. A field with no value, one that is not confirmed, or a value that
 * does not fit its type, is skipped and reported. - Each value becomes a contract_term row
 * (field_key, typed value, where it came from: the submission, the field result and its latest
 * revision number, who applied it and when). - A term whose value differs from the one in force
 * supersedes it (old row kept); an identical value is left alone. - lessor_name also finds or
 * creates the counterparty (case-insensitive name) and links it to the record when the record has
 * none yet; a record that already has a counterparty keeps it. - When several documents give the
 * same field, the first document with a value wins. Afterwards the submission becomes APPLIED, so
 * Apply cannot run twice for one submission, and the package moves from VALIDATED to COMPLETED
 * (Unsubmit and Decline then answer 409).
 */
@Service
@RequiredArgsConstructor
public class ApplyStore {

  public static final String SUBMISSION_APPLIED = "APPLIED";
  /**
   * Records that can receive values: not superseded, not archived.
   */
  static final List<String> TARGET_STATUS_CODES = List.of("ACTIVE", "PENDING");
  public static final String PACKAGE_COMPLETED = "COMPLETED";
  private static final String LESSOR_KEY = "lessor_name";

  private final SubmissionRepository submissionRepository;
  private final LkpSubmissionStatusRepository lkpSubmissionStatusRepository;
  private final ReviewStore reviewStore;
  private final ExtractionFieldRevisionRepository revisionRepository;
  private final ContractTermRepository termRepository;
  private final CounterpartyRepository counterpartyRepository;
  private final ContractRecordRepository contractRecordRepository;
  private final ContractPackageRepository contractPackageRepository;
  private final LkpPackageStatusRepository lkpPackageStatusRepository;
  private final ReviewerSeparation reviewerSeparation;
  /** ADDED 2026-10-16: closes the ECWS approval task. */
  private final EcwsTaskPublisher ecwsTaskPublisher;

  /**
   * The records a reviewer can choose when the package has no target: same workspace, active or
   * pending.
   */
  @Transactional(readOnly = true)
  public List<TargetCandidate> candidates(final Long packageId) {
    final var pkg = contractPackageRepository.findById(packageId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
    final List<TargetCandidate> rows = new ArrayList<>();
    for (final ContractRecord r : contractRecordRepository.findByWorkspace_IdAndStatus_CodeInOrderByRecordCode(
        pkg.getWorkspace().getId(), TARGET_STATUS_CODES)) {
      rows.add(new TargetCandidate(r.getId(), r.getRecordCode(), r.getContractType().getLabel(),
          r.getCounterparty() == null ? null : r.getCounterparty().getName(),
          r.getStatus().getLabel()));
    }
    return rows;
  }

  @Transactional
  public ApplyResult apply(final Long packageId, final Long targetRecordId, final String caller) {
    final Submission submission = submissionRepository.findByContractPackage_Id(packageId).stream()
        .max(Comparator.comparing(Submission::getId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "The package has no submission"));
    final String code = submission.getStatus().getCode();
    if (!ReviewStore.SUBMISSION_REVIEWED.equals(code)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, SUBMISSION_APPLIED.equals(code)
          ? "The reviewed values were already applied"
          : "Only a package whose review is completed can be applied");
    }
    reviewerSeparation.checkApprover(submission, caller);
    final var chosenFor = submission.getContractPackage();
    ContractRecord target = chosenFor.getTargetRecord();
    if (target == null) {
      target = chooseTarget(chosenFor, targetRecordId);
      chosenFor.setTargetRecord(target);
    }
    final ContractRecord record = target;
    final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    final Map<String, ContractTerm> inForce = new HashMap<>();
    for (final ContractTerm t : termRepository.findByContractRecord_IdAndSupersededAtIsNull(
        record.getId())) {
      inForce.put(t.getFieldKey(), t);
    }
    final Set<String> seen = new HashSet<>();
    final List<String> skipped = new ArrayList<>();
    int written = 0;
    int unchanged = 0;
    String lessor = null;
    for (final ExtractionFieldResult r : reviewStore.allResults(packageId)) {
      final ExtractionTemplateField f = r.getTemplateField();
      final String text = ReviewRules.normalize(ReviewStore.effective(r));
      if (text == null || seen.contains(f.getFieldKey())) {
        if (text == null && !seen.contains(f.getFieldKey())) {
          skipped.add(f.getLabel() + " (no value)");
        }
        continue;
      }
      if (!Boolean.TRUE.equals(r.getVerifiedFlag())) {
        skipped.add(f.getLabel() + " (not confirmed)");
        continue;
      }
      final String type = f.getDataType().getCode();
      final String error = ReviewRules.validate(type, text);
      if (error != null) {
        skipped.add(f.getLabel() + " (not a valid " + type.toLowerCase() + ")");
        continue;
      }
      seen.add(f.getFieldKey());
      if (LESSOR_KEY.equals(f.getFieldKey())) {
        lessor = text;
      }
      final ContractTerm term = newTerm(record, f, type, text, submission, r, caller, now);
      final ContractTerm current = inForce.get(f.getFieldKey());
      if (current != null && sameValue(current, term)) {
        unchanged++;
        continue;
      }
      if (current != null) {
        current.setSupersededAt(now);
        termRepository.save(current);
      }
      termRepository.save(term);
      written++;
    }
    final String[] counterparty = linkCounterparty(record, lessor);

    final int moved = submissionRepository.moveStatus(submission.getId(), submission.getStatus(),
        lkpSubmissionStatusRepository.findByCode(SUBMISSION_APPLIED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Missing lkp_submission_status row: APPLIED (run apply_to_contract.sql)")),
        now);
    if (moved != 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The reviewed values were already applied");
    }
    final var pkg = submission.getContractPackage();
    pkg.setStatus(lkpPackageStatusRepository.findByCode(PACKAGE_COMPLETED)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
            "Missing lkp_package_status row: COMPLETED (run package_completed.sql)")));
    contractPackageRepository.save(pkg);
    // ADDED 2026-10-16: nothing is left to do for this package.
    ecwsTaskPublisher.applied(packageId, caller);
    return new ApplyResult(written, unchanged, skipped, counterparty[0], counterparty[1],
        record.getRecordCode());
  }

  /**
   * The package has no target: the reviewer must have chosen a record of the package's workspace
   * that can receive values.
   */
  private ContractRecord chooseTarget(
      final com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage pkg,
      final Long targetRecordId) {
    if (targetRecordId == null) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "The package has no target contract record. Choose one.");
    }
    final ContractRecord chosen = contractRecordRepository.findById(targetRecordId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "The chosen contract record does not exist"));
    if (!chosen.getWorkspace().getId().equals(pkg.getWorkspace().getId())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "The chosen contract record is in another workspace");
    }
    final Collection<String> allowed = TARGET_STATUS_CODES;
    if (!allowed.contains(chosen.getStatus().getCode())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "The chosen contract record is " + chosen.getStatus().getLabel().toLowerCase()
              + " and cannot receive values");
    }
    return chosen;
  }

  /**
   * Returns {name, action}: CREATED, LINKED (existing counterparty linked), UNCHANGED (record has
   * one) or NONE.
   */
  private String[] linkCounterparty(final ContractRecord record, final String lessor) {
    if (lessor == null || lessor.length() > 255) {
      return new String[]{null, "NONE"};
    }
    if (record.getCounterparty() != null) {
      return new String[]{record.getCounterparty().getName(), "UNCHANGED"};
    }
    final var existing = counterpartyRepository.findByNameIgnoreCase(lessor);
    final Counterparty cp;
    final String action;
    if (existing.isPresent()) {
      cp = existing.get();
      action = "LINKED";
    } else {
      final Counterparty created = new Counterparty();
      created.setName(lessor);
      cp = counterpartyRepository.save(created);
      action = "CREATED";
    }
    record.setCounterparty(cp);
    contractRecordRepository.save(record);
    return new String[]{cp.getName(), action};
  }

  private ContractTerm newTerm(final ContractRecord record, final ExtractionTemplateField f,
      final String type,
      final String text, final Submission submission, final ExtractionFieldResult r,
      final String caller, final LocalDateTime now) {
    final ContractTerm t = new ContractTerm();
    t.setContractRecord(record);
    t.setFieldKey(f.getFieldKey());
    t.setLabel(f.getLabel());
    t.setDataType(type);
    t.setValueText(text);
    switch (type) {
      case "NUMBER" -> t.setValueNumber(new BigDecimal(text));
      case "DATE" -> t.setValueDate(LocalDate.parse(text));
      case "BOOLEAN" -> t.setValueBoolean(Boolean.valueOf(text));
      default -> {
      }
    }
    t.setSourceSubmissionId(submission.getId());
    t.setSourceFieldResultId(r.getId());
    t.setSourceRevisionNo(revisionRepository.maxRevisionNo(r.getId()));
    t.setAppliedBy(caller);
    t.setAppliedAt(now);
    return t;
  }

  private static boolean sameValue(final ContractTerm a, final ContractTerm b) {
    if (!a.getDataType().equals(b.getDataType())) {
      return false;
    }
    return switch (b.getDataType()) {
      case "NUMBER" ->
          a.getValueNumber() != null && a.getValueNumber().compareTo(b.getValueNumber()) == 0;
      case "DATE" -> b.getValueDate().equals(a.getValueDate());
      case "BOOLEAN" -> b.getValueBoolean().equals(a.getValueBoolean());
      default -> b.getValueText().equals(a.getValueText());
    };
  }
}