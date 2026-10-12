package com.dodaso.ecosystem.elcm.service.extraction;

import java.util.List;

/** What the reviewer did to one field, oldest first, with the value the extractor first produced. */
public record FieldHistory(Long resultId, String label, String extractedValue, List<Revision> revisions) {

    public record Revision(int revisionNo, String action, String previousValue, String newValue,
                           boolean confirmed, String changedBy, String changedAt, String reason) {
    }
}
