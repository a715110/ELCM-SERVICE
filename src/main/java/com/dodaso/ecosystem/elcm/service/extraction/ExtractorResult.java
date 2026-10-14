package com.dodaso.ecosystem.elcm.service.extraction;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** The extractor service's answer to POST /extract. Unknown properties are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractorResult(
    String template,
    String model,
    List<Field> fields,
    List<String> needsReview) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(
        String key,
        JsonNode value,
        String quote,
        String status,
        String problem,
        String note,
        List<String> candidates,
        Boolean required,
        Integer page) {
    }

    public List<Field> fieldsOrEmpty() {
        return fields == null ? List.of() : fields;
    }

    public List<String> needsReviewOrEmpty() {
        return needsReview == null ? List.of() : needsReview;
    }
}
