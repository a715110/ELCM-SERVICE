package com.dodaso.ecosystem.elcm.service.template;

import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Field;
import java.util.List;

/**
 * Shapes of the trial run of an extraction template (ADDED 2026-10-15). A trial run reads one
 * document with the fields of one template version and shows what the extractor found. Nothing is
 * saved.
 */
public final class TrialModels {

  private TrialModels() {}

  /** The document to read, sent as base64 text inside the JSON body. */
  public record TrialRequest(String fileName, String contentType, String contentBase64) {}

  /** What the extractor found for one field. value is null when nothing was found. */
  public record TrialField(
      String key,
      String label,
      String dataType,
      boolean required,
      boolean critical,
      String value,
      String status,
      String quote,
      Integer page,
      String problem,
      String note) {}

  /**
   * A trial run as it is now. state is QUEUED, RUNNING, DONE, FAILED or REFUSED (the request was not
   * accepted; message says why). fields and needsReview are filled when the state is DONE.
   */
  public record Trial(
      String id,
      String state,
      String message,
      Long templateId,
      String templateTitle,
      String fileName,
      String model,
      long seconds,
      List<TrialField> fields,
      List<String> needsReview) {}

  /** What a trial run needs from the stored template: the definition the extractor is given. */
  public record TrialDefinition(
      Long templateId, String title, String label, String json, List<Field> fields) {}
}
