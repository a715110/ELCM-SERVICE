package com.dodaso.ecosystem.elcm.service.contract;

import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecord;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractTerm;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractRecordRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractTermRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read side of the applied terms (ADDED 2026-10-12): the Contract Records list and the terms of
 * one record. Read only; Apply (ApplyStore) is the only writer of contract_term.
 *
 * list(q): every contract record with the number of terms in force and when the newest was
 * applied; q matches record code, counterparty, workspace or contract type (case-insensitive,
 * substring). Capped at 500 rows, newest applied first, then by record code.
 * terms(id, includeHistory): the record's terms in force, ordered by label; with includeHistory
 * the replaced values are added after the current ones of the same field, newest first.
 */
@Service
@RequiredArgsConstructor
public class ContractTermReadService {

    private static final int MAX_ROWS = 500;

    private final ContractRecordRepository contractRecordRepository;
    private final ContractTermRepository contractTermRepository;
    private final SubmissionRepository submissionRepository;

    @Transactional(readOnly = true)
    public List<ContractRecordSummary> list(final String q) {
        final String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        final Map<Long, int[]> counts = new HashMap<>();
        final Map<Long, LocalDateTime> last = new HashMap<>();
        for (final ContractTerm t : contractTermRepository.findBySupersededAtIsNull()) {
            final Long rid = t.getContractRecord().getId();
            counts.computeIfAbsent(rid, k -> new int[1])[0]++;
            last.merge(rid, t.getAppliedAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
        final List<ContractRecordSummary> rows = new ArrayList<>();
        for (final ContractRecord r : contractRecordRepository.findAll()) {
            final ContractRecordSummary s = summary(r, counts.get(r.getId()), last.get(r.getId()));
            if (needle.isEmpty() || matches(s, needle)) {
                rows.add(s);
            }
        }
        rows.sort(Comparator.comparing(ContractRecordSummary::lastAppliedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(ContractRecordSummary::recordCode, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows.size() > MAX_ROWS ? new ArrayList<>(rows.subList(0, MAX_ROWS)) : rows;
    }

    @Transactional(readOnly = true)
    public ContractRecordTerms terms(final Long recordId, final boolean includeHistory) {
        final ContractRecord record = contractRecordRepository.findById(recordId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract record not found."));
        final List<ContractTerm> current = contractTermRepository.findByContractRecord_IdAndSupersededAtIsNull(recordId);
        final List<ContractTerm> all = new ArrayList<>(current);
        if (includeHistory) {
            all.addAll(contractTermRepository.findByContractRecord_IdAndSupersededAtIsNotNull(recordId));
        }
        all.sort(Comparator.comparing((ContractTerm t) -> t.getLabel() == null ? "" : t.getLabel().toLowerCase(Locale.ROOT))
            .thenComparing(ContractTerm::getAppliedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        final Map<Long, String> packageCodes = new HashMap<>();
        final List<ContractTermView> views = new ArrayList<>();
        for (final ContractTerm t : all) {
            views.add(view(t, packageCodes));
        }
        LocalDateTime lastApplied = null;
        for (final ContractTerm t : current) {
            if (lastApplied == null || t.getAppliedAt().isAfter(lastApplied)) {
                lastApplied = t.getAppliedAt();
            }
        }
        return new ContractRecordTerms(summary(record, new int[] {current.size()}, lastApplied), views);
    }

    private ContractTermView view(final ContractTerm t, final Map<Long, String> packageCodes) {
        return new ContractTermView(t.getId(), t.getFieldKey(), t.getLabel(), t.getDataType(), valueText(t),
            t.getSupersededAt() == null, packageCode(t.getSourceSubmissionId(), packageCodes), t.getSourceSubmissionId(),
            t.getSourceRevisionNo(), t.getAppliedBy(),
            t.getAppliedAt() == null ? null : t.getAppliedAt().toString(),
            t.getSupersededAt() == null ? null : t.getSupersededAt().toString());
    }

    private String packageCode(final Long submissionId, final Map<Long, String> cache) {
        if (submissionId == null) {
            return null;
        }
        return cache.computeIfAbsent(submissionId, id -> {
            final Submission s = submissionRepository.findById(id).orElse(null);
            return s == null || s.getContractPackage() == null ? "" : s.getContractPackage().getPackageCode();
        });
    }

    /** The typed value as text; the NUMBER shows without trailing zeros. */
    static String valueText(final ContractTerm t) {
        final String type = t.getDataType() == null ? "STRING" : t.getDataType();
        switch (type) {
            case "NUMBER":
                return t.getValueNumber() == null ? t.getValueText() : t.getValueNumber().stripTrailingZeros().toPlainString();
            case "DATE":
                return t.getValueDate() == null ? t.getValueText() : t.getValueDate().toString();
            case "BOOLEAN":
                return t.getValueBoolean() == null ? t.getValueText() : (t.getValueBoolean() ? "Yes" : "No");
            default:
                return t.getValueText();
        }
    }

    private static ContractRecordSummary summary(final ContractRecord r, final int[] count, final LocalDateTime lastApplied) {
        return new ContractRecordSummary(r.getId(), r.getRecordCode(),
            r.getContractType() == null ? null : r.getContractType().getLabel(),
            r.getCounterparty() == null ? null : r.getCounterparty().getName(),
            r.getWorkspace() == null ? null : r.getWorkspace().getName(),
            r.getStatus() == null ? null : r.getStatus().getLabel(),
            count == null ? 0 : count[0], lastApplied == null ? null : lastApplied.toString());
    }

    private static boolean matches(final ContractRecordSummary s, final String needle) {
        for (final String v : new String[] {s.recordCode(), s.counterparty(), s.workspace(), s.contractType()}) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
