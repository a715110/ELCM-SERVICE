package com.dodaso.ecosystem.elcm.service.quality;

import com.dodaso.ecosystem.elcm.service.extraction.ApplyStore;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractionStore;
import com.dodaso.ecosystem.elcm.service.extraction.ReviewStore;
import com.dodaso.ecosystem.elcm.service.quality.ExtractionQualityReport.Operations;
import com.dodaso.ecosystem.elcm.service.quality.ExtractionQualityReport.Options;
import com.dodaso.ecosystem.elcm.service.quality.ExtractionQualityReport.Stat;
import com.dodaso.ecosystem.elcm.service.quality.ExtractionQualityReport.Summary;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Extraction accuracy, measured from what reviewers did (ADDED 2026-10-16).
 *
 * <p>Nothing is stored for this report. It reads the field results of every completed review
 * (newest submission of the package is REVIEWED or APPLIED, the extraction job is COMPLETED, so
 * jobs set aside by a decline or a re-read are left out) and works out, per field, what the
 * reviewer did. Earlier reviews are counted automatically, and a data clean-up resets the numbers.
 *
 * <p>Outcome of a field (see classify): the reviewer kept the value (ACCEPTED), changed it
 * (CORRECTED), filled a field the extractor left empty (MISSED), the field stayed empty
 * (NOT_FOUND), or nobody confirmed or changed it (UNCONFIRMED). Accuracy is ACCEPTED divided by
 * ACCEPTED plus CORRECTED plus MISSED. A correction that only changes formatting (currency symbol,
 * commas, spaces, case, trailing zeros) does not count as wrong.
 *
 * <p>Limit: a reviewer cannot blank a value, so "found something that should be empty" is not
 * measured. Also, "confirm all verified" is one click, so accuracy shows how much was kept, which
 * is only as good as the review behind it.
 */
@Service
@RequiredArgsConstructor
public class ExtractionQualityService {

  private static final int DEFAULT_DAYS = 90;
  private static final int MAX_FIELD_ROWS = 200;
  private static final String ALL = "";
  private static final String NO_VALUE = "(none)";

  private final EntityManager entityManager;

  /** What the reviewer did with one field. */
  enum Outcome {
    ACCEPTED,
    CORRECTED,
    MISSED,
    NOT_FOUND,
    UNCONFIRMED
  }

  /** One field of a completed review, flattened for counting. */
  private record Row(
      Outcome outcome,
      boolean flagged,
      boolean critical,
      String fieldKey,
      String fieldLabel,
      String model,
      String template,
      String workspace,
      String band,
      int bandOrder,
      Long documentId,
      LocalDate reviewedOn) {

    boolean decided() {
      return outcome == Outcome.ACCEPTED
          || outcome == Outcome.CORRECTED
          || outcome == Outcome.MISSED;
    }
  }

  /** Running counts for one row of a breakdown. */
  private static final class Tally {
    long accepted;
    long corrected;
    long missed;
    long criticalAccepted;
    long criticalDecided;
    boolean critical;

    void add(final Row row) {
      if (!row.decided()) {
        return;
      }
      switch (row.outcome()) {
        case ACCEPTED -> accepted++;
        case CORRECTED -> corrected++;
        default -> missed++;
      }
      if (row.critical()) {
        criticalDecided++;
        if (row.outcome() == Outcome.ACCEPTED) {
          criticalAccepted++;
        }
      }
      critical = row.critical();
    }

    long decided() {
      return accepted + corrected + missed;
    }

    Stat toStat(final String label) {
      return new Stat(
          label,
          decided(),
          accepted,
          corrected,
          missed,
          percent(accepted, decided()),
          criticalDecided,
          percent(criticalAccepted, criticalDecided),
          critical);
    }
  }

  /**
   * Builds the report.
   *
   * @param from first day, yyyy-mm-dd, default 90 days ago
   * @param to last day, yyyy-mm-dd, default today
   * @param workspace workspace name, blank for all
   * @param template template name and version, blank for all
   * @param model model name, blank for all
   * @param granularity WEEK or MONTH
   */
  @Transactional(readOnly = true)
  public ExtractionQualityReport report(
      final String from,
      final String to,
      final String workspace,
      final String template,
      final String model,
      final String granularity) {
    final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate firstDay = parse(from, today.minusDays(DEFAULT_DAYS));
    LocalDate lastDay = parse(to, today);
    if (lastDay.isBefore(firstDay)) {
      final LocalDate swap = firstDay;
      firstDay = lastDay;
      lastDay = swap;
    }
    final LocalDateTime start = firstDay.atStartOfDay();
    final LocalDateTime end = lastDay.plusDays(1).atStartOfDay();
    final boolean monthly = "MONTH".equalsIgnoreCase(granularity);

    final List<Row> all = loadRows(start, end);

    final Set<String> workspaces = new TreeSet<>();
    final Set<String> templates = new TreeSet<>();
    final Set<String> models = new TreeSet<>();
    for (final Row r : all) {
      workspaces.add(r.workspace());
      templates.add(r.template());
      models.add(r.model());
    }

    final List<Row> rows = new ArrayList<>();
    for (final Row r : all) {
      if (matches(workspace, r.workspace())
          && matches(template, r.template())
          && matches(model, r.model())) {
        rows.add(r);
      }
    }

    return new ExtractionQualityReport(
        summary(rows),
        trend(rows, monthly),
        breakdown(rows, Row::template),
        breakdown(rows, Row::model),
        breakdown(rows, Row::workspace),
        fieldBreakdown(rows),
        confidence(rows),
        operations(start, end),
        new Options(
            new ArrayList<>(workspaces), new ArrayList<>(templates), new ArrayList<>(models)));
  }

  /** The field results of completed reviews inside the date range. */
  private List<Row> loadRows(final LocalDateTime start, final LocalDateTime end) {
    final List<Object[]> raw =
        entityManager
            .createQuery(
                "select r.extractedValue, r.correctedValue, r.verifiedFlag, r.needsReviewFlag, "
                    + "tf.isCritical, tf.fieldKey, tf.label, j.modelName, t.name, t.version, "
                    + "w.name, ct.label, ct.sortOrder, d.id, s.reviewedAt "
                    + "from ExtractionFieldResult r "
                    + "join r.job j join j.stagedDocument d join j.template t "
                    + "join r.templateField tf "
                    + "left join r.confidenceTier ct left join d.workspace w "
                    + "join PackageDocument pd on pd.stagedDocument = d "
                    + "join Submission s on s.contractPackage = pd.contractPackage "
                    + "where s.id = (select max(s2.id) from Submission s2 "
                    + "where s2.contractPackage = pd.contractPackage) "
                    + "and s.status.code in :done and j.status.code = :completed "
                    + "and s.reviewedAt >= :start and s.reviewedAt < :end",
                Object[].class)
            .setParameter(
                "done", List.of(ReviewStore.SUBMISSION_REVIEWED, ApplyStore.SUBMISSION_APPLIED))
            .setParameter("completed", ExtractionStore.JOB_COMPLETED)
            .setParameter("start", start)
            .setParameter("end", end)
            .getResultList();

    final List<Row> rows = new ArrayList<>(raw.size());
    for (final Object[] c : raw) {
      final String extracted = (String) c[0];
      final String corrected = (String) c[1];
      final Boolean verified = (Boolean) c[2];
      final Boolean needsReview = (Boolean) c[3];
      final Boolean critical = (Boolean) c[4];
      final LocalDateTime reviewedAt = (LocalDateTime) c[14];
      rows.add(
          new Row(
              classify(extracted, corrected, verified),
              Boolean.TRUE.equals(needsReview),
              Boolean.TRUE.equals(critical),
              (String) c[5],
              (String) c[6],
              orNone((String) c[7]),
              orNone((String) c[8]) + (c[9] == null ? "" : " v" + c[9]),
              orNone((String) c[10]),
              c[11] == null ? "No confidence band" : (String) c[11],
              c[12] == null ? Integer.MAX_VALUE : ((Number) c[12]).intValue(),
              (Long) c[13],
              reviewedAt.toLocalDate()));
    }
    return rows;
  }

  /**
   * What the reviewer did with a field.
   *
   * <p>A saved correction is the reviewer's final value: it counts as CORRECTED when it differs from
   * the extracted one, ACCEPTED when it only restates it, and MISSED when nothing was extracted.
   * Without a correction, a confirmed value is ACCEPTED, an unconfirmed one is UNCONFIRMED, and an
   * empty one is NOT_FOUND.
   */
  static Outcome classify(final String extracted, final String corrected, final Boolean verified) {
    final String found = blankToNull(extracted);
    final String fixed = blankToNull(corrected);
    if (fixed != null) {
      if (found == null) {
        return Outcome.MISSED;
      }
      return sameValue(found, fixed) ? Outcome.ACCEPTED : Outcome.CORRECTED;
    }
    if (found == null) {
      return Outcome.NOT_FOUND;
    }
    return Boolean.TRUE.equals(verified) ? Outcome.ACCEPTED : Outcome.UNCONFIRMED;
  }

  /** Equal ignoring case, spaces, currency symbol, commas and trailing zeros of numbers. */
  static boolean sameValue(final String a, final String b) {
    final String x = squash(a);
    final String y = squash(b);
    if (x.equals(y)) {
      return true;
    }
    try {
      return new BigDecimal(x).compareTo(new BigDecimal(y)) == 0;
    } catch (final NumberFormatException e) {
      return false;
    }
  }

  private static String squash(final String value) {
    return value.toLowerCase(Locale.ROOT).replace("usd", "").replaceAll("[$,\\s]", "");
  }

  private Summary summary(final List<Row> rows) {
    final Tally all = new Tally();
    long notFound = 0;
    long unconfirmed = 0;
    long flaggedDecided = 0;
    long flaggedWrong = 0;
    final Set<Long> documents = new HashSet<>();
    for (final Row r : rows) {
      documents.add(r.documentId());
      all.add(r);
      if (r.outcome() == Outcome.NOT_FOUND) {
        notFound++;
      } else if (r.outcome() == Outcome.UNCONFIRMED) {
        unconfirmed++;
      }
      if (r.decided() && r.flagged()) {
        flaggedDecided++;
        if (r.outcome() != Outcome.ACCEPTED) {
          flaggedWrong++;
        }
      }
    }
    return new Summary(
        documents.size(),
        rows.size(),
        all.decided(),
        all.accepted,
        all.corrected,
        all.missed,
        notFound,
        unconfirmed,
        percent(all.accepted, all.decided()),
        all.criticalDecided,
        percent(all.criticalAccepted, all.criticalDecided),
        flaggedDecided,
        percent(flaggedWrong, flaggedDecided));
  }

  private List<Stat> trend(final List<Row> rows, final boolean monthly) {
    final Map<String, Tally> tallies = new TreeMap<>();
    for (final Row r : rows) {
      final String key =
          monthly
              ? r.reviewedOn().toString().substring(0, 7)
              : r.reviewedOn().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
      tallies.computeIfAbsent(key, k -> new Tally()).add(r);
    }
    final List<Stat> out = new ArrayList<>();
    tallies.forEach((k, t) -> out.add(t.toStat(monthly ? k : "Week of " + k)));
    return out;
  }

  private List<Stat> breakdown(
      final List<Row> rows, final java.util.function.Function<Row, String> key) {
    final Map<String, Tally> tallies = new TreeMap<>();
    for (final Row r : rows) {
      tallies.computeIfAbsent(key.apply(r), k -> new Tally()).add(r);
    }
    final List<Stat> out = new ArrayList<>();
    tallies.forEach((k, t) -> out.add(t.toStat(k)));
    return out;
  }

  private List<Stat> fieldBreakdown(final List<Row> rows) {
    final Map<String, Tally> tallies = new TreeMap<>();
    final Map<String, String> labels = new TreeMap<>();
    for (final Row r : rows) {
      tallies.computeIfAbsent(r.fieldKey(), k -> new Tally()).add(r);
      labels.putIfAbsent(r.fieldKey(), r.fieldLabel());
    }
    final List<Stat> out = new ArrayList<>();
    tallies.forEach(
        (k, t) -> {
          if (t.decided() > 0) {
            out.add(t.toStat(labels.get(k)));
          }
        });
    out.sort(
        Comparator.comparingDouble(
                (Stat s) -> s.accuracyPct() == null ? 101.0 : s.accuracyPct())
            .thenComparing(Stat::label));
    return out.size() > MAX_FIELD_ROWS ? new ArrayList<>(out.subList(0, MAX_FIELD_ROWS)) : out;
  }

  private List<Stat> confidence(final List<Row> rows) {
    final Map<Integer, Tally> tallies = new TreeMap<>();
    final Map<Integer, String> labels = new TreeMap<>();
    for (final Row r : rows) {
      tallies.computeIfAbsent(r.bandOrder(), k -> new Tally()).add(r);
      labels.putIfAbsent(r.bandOrder(), r.band());
    }
    final List<Stat> out = new ArrayList<>();
    tallies.forEach((k, t) -> out.add(t.toStat(labels.get(k))));
    return out;
  }

  private Operations operations(final LocalDateTime start, final LocalDateTime end) {
    final List<Object[]> jobs =
        entityManager
            .createQuery(
                "select j.status.code, count(j), avg(j.durationSeconds), "
                    + "coalesce(sum(case when j.attemptCount > 1 then 1 else 0 end), 0) "
                    + "from ExtractionJob j where j.createdAt >= :start and j.createdAt < :end "
                    + "group by j.status.code",
                Object[].class)
            .setParameter("start", start)
            .setParameter("end", end)
            .getResultList();
    long total = 0;
    long completed = 0;
    long failed = 0;
    long retried = 0;
    double seconds = 0;
    long timed = 0;
    for (final Object[] c : jobs) {
      final long n = ((Number) c[1]).longValue();
      total += n;
      retried += ((Number) c[3]).longValue();
      if (ExtractionStore.JOB_COMPLETED.equals(c[0])) {
        completed += n;
      } else if (ExtractionStore.JOB_FAILED.equals(c[0])) {
        failed += n;
      }
      if (c[2] != null) {
        seconds += ((Number) c[2]).doubleValue() * n;
        timed += n;
      }
    }
    final long reviewed =
        ((Number)
                entityManager
                    .createQuery(
                        "select count(s) from Submission s where s.status.code in :done "
                            + "and s.reviewedAt >= :start and s.reviewedAt < :end")
                    .setParameter(
                        "done",
                        List.of(ReviewStore.SUBMISSION_REVIEWED, ApplyStore.SUBMISSION_APPLIED))
                    .setParameter("start", start)
                    .setParameter("end", end)
                    .getSingleResult())
            .longValue();
    final long declined =
        ((Number)
                entityManager
                    .createQuery(
                        "select count(s) from Submission s where s.status.code = :declined "
                            + "and s.declinedAt >= :start and s.declinedAt < :end")
                    .setParameter("declined", ReviewStore.SUBMISSION_DECLINED)
                    .setParameter("start", start)
                    .setParameter("end", end)
                    .getSingleResult())
            .longValue();
    return new Operations(
        total,
        completed,
        failed,
        retried,
        timed == 0 ? null : round1(seconds / timed),
        reviewed,
        declined);
  }

  private static boolean matches(final String wanted, final String actual) {
    return wanted == null || wanted.isBlank() || ALL.equals(wanted) || wanted.equals(actual);
  }

  private static LocalDate parse(final String text, final LocalDate fallback) {
    if (text == null || text.isBlank()) {
      return fallback;
    }
    try {
      return LocalDate.parse(text.trim());
    } catch (final DateTimeParseException e) {
      return fallback;
    }
  }

  private static String orNone(final String value) {
    return value == null || value.isBlank() ? NO_VALUE : value;
  }

  private static String blankToNull(final String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  private static Double percent(final long part, final long whole) {
    return whole == 0 ? null : round1(part * 100.0 / whole);
  }

  private static double round1(final double value) {
    return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
  }
}
