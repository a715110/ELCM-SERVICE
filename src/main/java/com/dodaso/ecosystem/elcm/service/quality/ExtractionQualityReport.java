package com.dodaso.ecosystem.elcm.service.quality;

import java.util.List;

/**
 * The Extraction Quality report (ADDED 2026-10-16), read by the admin screen.
 *
 * <p>Accuracy is measured on reviewed fields only: a field counts as accepted when the reviewer
 * kept the extracted value, as corrected when the reviewer changed it, and as missed when the
 * extractor found nothing and the reviewer typed a value. Fields nobody confirmed or changed, and
 * fields where nothing was found and nothing was added, are counted apart and left out of the
 * percentages.
 *
 * @param summary the headline numbers
 * @param trend accuracy per week or month, oldest first
 * @param byTemplate accuracy per template and version
 * @param byModel accuracy per model
 * @param byWorkspace accuracy per workspace
 * @param fields accuracy per field, most corrected first
 * @param confidence accuracy per confidence band
 * @param operations job and review counts for the same period
 * @param options the values for the filter lists
 */
public record ExtractionQualityReport(
    Summary summary,
    List<Stat> trend,
    List<Stat> byTemplate,
    List<Stat> byModel,
    List<Stat> byWorkspace,
    List<Stat> fields,
    List<Stat> confidence,
    Operations operations,
    Options options) {

  /**
   * The headline numbers.
   *
   * @param documents reviewed documents behind the numbers
   * @param fieldsTotal all fields of those documents
   * @param decided accepted plus corrected plus missed
   * @param accepted fields the reviewer kept
   * @param corrected fields the reviewer changed
   * @param missed fields found empty that the reviewer filled
   * @param notFound fields found empty and left empty
   * @param unconfirmed fields found, not confirmed and not changed
   * @param accuracyPct accepted as a percent of decided, null when nothing is decided
   * @param criticalDecided decided fields that are marked critical in the template
   * @param criticalAccuracyPct the same percent for critical fields only
   * @param flaggedDecided decided fields the extractor marked "needs review"
   * @param flaggedWrongPct share of those that the reviewer had to change
   */
  public record Summary(
      long documents,
      long fieldsTotal,
      long decided,
      long accepted,
      long corrected,
      long missed,
      long notFound,
      long unconfirmed,
      Double accuracyPct,
      long criticalDecided,
      Double criticalAccuracyPct,
      long flaggedDecided,
      Double flaggedWrongPct) {}

  /**
   * One row of a breakdown: a period, a template, a model, a workspace, a field or a confidence
   * band.
   *
   * @param label what the row stands for
   * @param decided accepted plus corrected plus missed
   * @param accepted fields the reviewer kept
   * @param corrected fields the reviewer changed
   * @param missed fields found empty that the reviewer filled
   * @param accuracyPct accepted as a percent of decided, null when nothing is decided
   * @param criticalDecided decided critical fields
   * @param criticalAccuracyPct the percent for critical fields only
   * @param critical true when the row is a field marked critical (fields breakdown only)
   */
  public record Stat(
      String label,
      long decided,
      long accepted,
      long corrected,
      long missed,
      Double accuracyPct,
      long criticalDecided,
      Double criticalAccuracyPct,
      boolean critical) {}

  /**
   * Job and review counts for the date range, all workspaces and templates.
   *
   * @param jobs extraction jobs started
   * @param completed jobs that finished
   * @param failed jobs that failed
   * @param retried jobs that needed more than one attempt
   * @param avgSeconds average read time of the jobs that have one
   * @param reviewed review submissions completed
   * @param declined review submissions declined
   */
  public record Operations(
      long jobs,
      long completed,
      long failed,
      long retried,
      Double avgSeconds,
      long reviewed,
      long declined) {}

  /**
   * The values the filter lists offer.
   *
   * @param workspaces workspace names seen in the period
   * @param templates template names with version seen in the period
   * @param models model names seen in the period
   */
  public record Options(List<String> workspaces, List<String> templates, List<String> models) {}
}
