package com.dodaso.ecosystem.elcm.service.template;

import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionTemplate;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionTemplateField;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Field;
import java.util.List;

/**
 * Turns the stored template into what the extractor needs (ADDED 2026-10-14). Call inside a
 * transaction: the data type of a field is loaded lazily.
 */
public final class TemplateDefinitions {

  private TemplateDefinitions() {}

  /** One stored field as the shape used by the rules and the admin API. */
  public static Field toField(final ExtractionTemplateField f) {
    return new Field(
        f.getId(),
        f.getFieldKey(),
        f.getLabel(),
        f.getDataType().getCode(),
        Boolean.TRUE.equals(f.getIsCritical()),
        Boolean.TRUE.equals(f.getIsRequired()),
        f.getDisplayOrder() == null ? 0 : f.getDisplayOrder(),
        f.getDescription(),
        f.getKeywords(),
        f.getAllowedValues(),
        f.getValueHints());
  }

  /** The label sent to the extractor: its template id when there is one, else a made up one. */
  public static String label(final ExtractionTemplate t) {
    final String id = t.getExtractorTemplateId();
    return id != null && !id.isBlank()
        ? id
        : TemplateRules.slug(t.getName()) + "_v" + t.getVersion();
  }

  /**
   * The definition to send with an extraction request, as JSON. Returns null when the template
   * predates template management (no field has a description) and has an extractor template id: the
   * extractor then reads its own YAML file of that name, as it did before.
   */
  public static String forExtractor(
      final ExtractionTemplate t, final List<ExtractionTemplateField> fields) {
    final boolean legacy =
        t.getExtractorTemplateId() != null
            && !t.getExtractorTemplateId().isBlank()
            && fields.stream()
                .allMatch(f -> f.getDescription() == null || f.getDescription().isBlank());
    if (legacy) {
      return null;
    }
    return TemplateRules.definitionJson(
        label(t), t.getName(), fields.stream().map(TemplateDefinitions::toField).toList());
  }
}
