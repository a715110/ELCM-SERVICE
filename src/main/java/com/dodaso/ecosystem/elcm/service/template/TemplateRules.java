package com.dodaso.ecosystem.elcm.service.template;

import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Change;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Field;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules for an extraction template, kept free of database and web code so they can be tested on
 * their own (ADDED 2026-10-14): what a valid field list is, what differs between two versions, and
 * the definition sent to the extractor.
 */
public final class TemplateRules {

  public static final int MAX_FIELDS = 100;
  public static final int MIN_REASON = 10;
  public static final int MAX_REASON = 500;

  private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,99}$");
  private static final ObjectMapper JSON = new ObjectMapper();

  private TemplateRules() {}

  /** Trims every text, turns blank into null, and cleans the comma separated lists. */
  public static Field normalize(final Field f) {
    return new Field(
        f.id(),
        blankToNull(f.key()),
        blankToNull(f.label()),
        blankToNull(f.dataType()),
        f.critical(),
        f.required(),
        f.order(),
        blankToNull(f.description()),
        blankToNull(String.join(",", splitList(f.keywords()))),
        blankToNull(String.join(",", splitList(f.allowedValues()))),
        normalizeHints(f.valueHints()));
  }

  /** Messages for everything wrong with the field list; empty when it is acceptable. */
  public static List<String> problems(final List<Field> fields, final Set<String> typeCodes) {
    final List<String> out = new ArrayList<>();
    if (fields.size() > MAX_FIELDS) {
      out.add("A template can have at most " + MAX_FIELDS + " fields.");
    }
    final Set<String> seen = new LinkedHashSet<>();
    int n = 0;
    for (final Field f : fields) {
      n++;
      final String who = f.key() == null ? "Field " + n : f.key();
      if (f.key() == null || !KEY.matcher(f.key()).matches()) {
        out.add(
            who
                + ": the key must start with a lower case letter and use only lower case letters,"
                + " digits and underscores (for example base_rent_monthly).");
      } else if (!seen.add(f.key())) {
        out.add(who + ": the key is used twice.");
      }
      if (f.label() == null) {
        out.add(who + ": the label is empty.");
      } else if (f.label().length() > 200) {
        out.add(who + ": the label is longer than 200 characters.");
      }
      if (f.dataType() == null || !typeCodes.contains(f.dataType())) {
        out.add(who + ": the data type is not one of " + String.join(", ", typeCodes) + ".");
      }
      if (f.description() != null && f.description().length() > 1000) {
        out.add(who + ": the description is longer than 1000 characters.");
      }
      if (f.keywords() != null && f.keywords().length() > 500) {
        out.add(who + ": the keywords are longer than 500 characters.");
      }
      if (f.allowedValues() != null && f.allowedValues().length() > 1000) {
        out.add(who + ": the allowed values are longer than 1000 characters.");
      }
      if (f.valueHints() != null && f.valueHints().length() > 2000) {
        out.add(who + ": the value hints are longer than 2000 characters.");
      }
      final List<String> allowed = splitList(f.allowedValues());
      if (!allowed.isEmpty() && !"string".equals(extractorType(f.dataType()))) {
        out.add(who + ": allowed values only make sense for a text field.");
      }
      if (f.valueHints() != null) {
        for (final String line : f.valueHints().split("\n")) {
          final int colon = line.indexOf(':');
          if (colon < 1 || line.substring(colon + 1).trim().isEmpty()) {
            out.add(
                who
                    + ": a value hint must look like VALUE: word one, word two (got: "
                    + line
                    + ").");
          } else if (!allowed.contains(line.substring(0, colon).trim())) {
            out.add(
                who
                    + ": the hint for "
                    + line.substring(0, colon).trim()
                    + " is not one of the allowed values.");
          }
        }
      }
    }
    return out;
  }

  /** What changed going from before to after, by field key. Order of fields is not compared. */
  public static List<Change> diff(final List<Field> before, final List<Field> after) {
    final Map<String, Field> old = new LinkedHashMap<>();
    before.forEach(f -> old.put(f.key(), f));
    final Map<String, Field> now = new LinkedHashMap<>();
    after.forEach(f -> now.put(f.key(), f));
    final List<Change> out = new ArrayList<>();
    for (final Field f : after) {
      final Field o = old.get(f.key());
      if (o == null) {
        out.add(new Change("ADDED", f.key(), f.label(), "New field."));
        continue;
      }
      final List<String> what = new ArrayList<>();
      if (!Objects.equals(o.label(), f.label())) what.add("label");
      if (!Objects.equals(o.dataType(), f.dataType())) what.add("data type");
      if (o.critical() != f.critical()) what.add("critical");
      if (o.required() != f.required()) what.add("required");
      if (!Objects.equals(o.description(), f.description())) what.add("description");
      if (!Objects.equals(o.keywords(), f.keywords())) what.add("keywords");
      if (!Objects.equals(o.allowedValues(), f.allowedValues())) what.add("allowed values");
      if (!Objects.equals(o.valueHints(), f.valueHints())) what.add("value hints");
      if (!what.isEmpty()) {
        out.add(new Change("CHANGED", f.key(), f.label(), String.join(", ", what)));
      }
    }
    for (final Field o : before) {
      if (!now.containsKey(o.key())) {
        out.add(new Change("REMOVED", o.key(), o.label(), "Field removed."));
      }
    }
    return out;
  }

  /** One short text for the change log, at most 2000 characters. */
  public static String summary(final List<Change> changes) {
    if (changes.isEmpty()) {
      return "No change to the fields.";
    }
    long added = changes.stream().filter(c -> "ADDED".equals(c.kind())).count();
    long removed = changes.stream().filter(c -> "REMOVED".equals(c.kind())).count();
    long changed = changes.stream().filter(c -> "CHANGED".equals(c.kind())).count();
    final StringBuilder sb =
        new StringBuilder()
            .append(added)
            .append(" added, ")
            .append(changed)
            .append(" changed, ")
            .append(removed)
            .append(" removed. ");
    for (final Change c : changes) {
      sb.append(c.kind().charAt(0))
          .append(c.kind().substring(1).toLowerCase())
          .append(' ')
          .append(c.fieldKey());
      if ("CHANGED".equals(c.kind())) {
        sb.append(" (").append(c.what()).append(')');
      }
      sb.append("; ");
    }
    return sb.length() <= 2000 ? sb.toString().trim() : sb.substring(0, 1996) + "...";
  }

  /** Comma separated text to a clean list: trimmed, no empty entries, no repeats, order kept. */
  public static List<String> splitList(final String csv) {
    final Set<String> out = new LinkedHashSet<>();
    if (csv != null) {
      for (final String part : csv.split(",")) {
        final String p = part.trim();
        if (!p.isEmpty()) {
          out.add(p);
        }
      }
    }
    return new ArrayList<>(out);
  }

  /** VALUE: word, word lines to a map; lines that do not parse are skipped. */
  public static Map<String, List<String>> parseHints(final String text) {
    final Map<String, List<String>> out = new LinkedHashMap<>();
    if (text != null) {
      for (final String line : text.split("\n")) {
        final int colon = line.indexOf(':');
        if (colon > 0) {
          final List<String> words = splitList(line.substring(colon + 1));
          if (!words.isEmpty()) {
            out.put(line.substring(0, colon).trim(), words);
          }
        }
      }
    }
    return out;
  }

  /** The extractor's type name for a data type code of the lookup table. */
  public static String extractorType(final String dataTypeCode) {
    if (dataTypeCode == null) {
      return "string";
    }
    return switch (dataTypeCode.toUpperCase(java.util.Locale.ROOT)) {
      case "NUMBER" -> "number";
      case "DATE" -> "date";
      case "BOOLEAN" -> "boolean";
      default -> "string";
    };
  }

  /**
   * The template as the extractor reads it (the same shape as its YAML files): id, name and the
   * fields with type, description, required, keywords, enum and enum_hints. A field without a
   * description is described by its label.
   */
  public static String definitionJson(
      final String id, final String name, final List<Field> fields) {
    final Map<String, Object> template = new LinkedHashMap<>();
    template.put("id", id);
    template.put("name", name);
    final List<Map<String, Object>> list = new ArrayList<>();
    for (final Field f : fields) {
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("key", f.key());
      m.put("type", extractorType(f.dataType()));
      m.put("description", f.description() != null ? f.description() : f.label());
      m.put("required", f.required());
      final List<String> keywords = splitList(f.keywords());
      if (!keywords.isEmpty()) {
        m.put("keywords", keywords);
      }
      final List<String> allowed = splitList(f.allowedValues());
      if (!allowed.isEmpty()) {
        m.put("enum", allowed);
      }
      final Map<String, List<String>> hints = parseHints(f.valueHints());
      if (!hints.isEmpty()) {
        m.put("enum_hints", hints);
      }
      list.add(m);
    }
    template.put("fields", list);
    try {
      return JSON.writeValueAsString(template);
    } catch (final JsonProcessingException e) {
      throw new IllegalStateException("The template could not be written as JSON", e);
    }
  }

  /** The number to use for the version after the given labels ("1", "2", "1.0" and so on). */
  public static String nextVersion(final List<String> labels) {
    int max = 0;
    for (final String label : labels) {
      final java.util.regex.Matcher m =
          Pattern.compile("^\\D*(\\d+)").matcher(label == null ? "" : label);
      if (m.find()) {
        max = Math.max(max, Integer.parseInt(m.group(1)));
      }
    }
    return Integer.toString(max + 1);
  }

  /** A label for the extractor made from a template name: "Property Lease" gives property_lease. */
  public static String slug(final String name) {
    final String s =
        name == null
            ? ""
            : name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    return s.isEmpty() ? "template" : s;
  }

  private static String normalizeHints(final String hints) {
    if (hints == null) {
      return null;
    }
    final List<String> lines = new ArrayList<>();
    for (final String line : hints.replace("\r", "").split("\n")) {
      final int colon = line.indexOf(':');
      if (colon < 0) {
        if (!line.trim().isEmpty()) {
          lines.add(line.trim());
        }
        continue;
      }
      lines.add(
          line.substring(0, colon).trim()
              + ": "
              + String.join(", ", splitList(line.substring(colon + 1))));
    }
    return blankToNull(String.join("\n", lines));
  }

  private static String blankToNull(final String s) {
    if (s == null) {
      return null;
    }
    final String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
