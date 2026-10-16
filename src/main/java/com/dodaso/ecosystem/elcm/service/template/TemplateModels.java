package com.dodaso.ecosystem.elcm.service.template;

import java.util.List;

/**
 * Request and answer shapes of the extraction template administration API (ADDED 2026-10-14). Times
 * are ISO strings in UTC.
 */
public final class TemplateModels {

  private TemplateModels() {}

  /** One field of a template version, as edited on the admin screen. */
  public record Field(
      Long id,
      String key,
      String label,
      String dataType,
      boolean critical,
      boolean required,
      int order,
      String description,
      String keywords,
      String allowedValues,
      String valueHints) {}

  /** One version of a template, without its fields. */
  public record Summary(
      Long id,
      String name,
      Long contractTypeId,
      String contractType,
      String version,
      String status,
      boolean active,
      int fieldCount,
      String changeReason,
      Long basedOnId,
      String publishedBy,
      String publishedAt,
      String updatedBy,
      String updatedAt) {}

  /** A version with its fields; openDraftId is the open draft of the same template, if any. */
  public record Detail(Summary summary, List<Field> fields, Long openDraftId) {}

  /** One entry of the change log of a template. */
  public record Event(
      Long templateId,
      String versionLabel,
      String eventType,
      String reason,
      String summary,
      String createdBy,
      String createdAt) {}

  /** One difference between two versions. kind is ADDED, REMOVED or CHANGED. */
  public record Change(String kind, String fieldKey, String label, String what) {}

  /** What differs between two versions of a template. */
  public record Diff(
      Long fromId, String fromVersion, Long toId, String toVersion, List<Change> changes) {}

  /** A choice in a list: a data type or a contract type. */
  public record Option(Long id, String code, String label) {}

  /** The lists the admin screen needs. */
  public record Options(List<Option> dataTypes, List<Option> contractTypes) {}

  /**
   * The outcome of save, publish or create. When problems is not empty nothing was changed and the
   * messages say why; otherwise detail is the version as it is now.
   */
  public record Result(List<String> problems, Detail detail) {}

  /** The whole field list of a draft. */
  public record SaveRequest(List<Field> fields) {}

  /** A reason typed by the admin (publish needs one; discard may have one). */
  public record ReasonRequest(String reason) {}

  /** Start a draft from an existing version. */
  public record CreateDraftRequest(Long basedOnId, String reason) {}

  /** Start a brand new template (first version is an empty draft). */
  public record NewTemplateRequest(String name, Long contractTypeId, String reason) {}

  /**
   * ADDED 2026-10-15 - Delete a whole template (every version) for good. confirmName must be the
   * template's name; reason is kept in the change log.
   */
  public record DeleteRequest(String confirmName, String reason) {}

  /**
   * ADDED 2026-10-15 - What deleting a template would remove. jobs is the number of extraction jobs
   * (documents read) that used any version; when it is more than zero the template cannot be
   * deleted.
   */
  public record DeleteImpact(
      String name, String contractType, int versions, boolean published, long jobs) {}
}
