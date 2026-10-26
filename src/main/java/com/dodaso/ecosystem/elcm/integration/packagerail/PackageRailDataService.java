package com.dodaso.ecosystem.elcm.integration.packagerail;

import com.dodaso.ecosystem.elcm.integration.ecws.TaskRailSubmissionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADDED 2026-10-25 -- the database side of the Package Rail: one read-only transaction, no
 * outbound calls. Display names are added later by {@link PackageRailService}.
 */
@Service
@RequiredArgsConstructor
public class PackageRailDataService {

  /** The package status that ends a package; every other status is "open". */
  static final String CLOSED_CODE = "COMPLETED";

  private final PackageRailRepository packages;
  private final TaskRailSubmissionRepository submissions;

  /** A page of lines with their document counts and who submitted and reviewed each. */
  public record Seen(PackageRailRepository.Line line, int docCount, String submittedBy,
                     String reviewedBy) {
  }

  /** The lines of one page and the number of packages in the whole list. */
  public record Slice(List<Seen> items, long total) {
  }

  @Transactional(readOnly = true)
  public Slice read(final boolean closed, final boolean all, final String login,
      final int offset, final int size) {
    final String key = login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
    final PageRequest page = PageRequest.of(Math.max(0, offset) / size, size);
    final Page<PackageRailRepository.Line> found = closed
        ? packages.findClosed(CLOSED_CODE, all, key, page)
        : packages.findOpen(CLOSED_CODE, all, key, page);
    final List<PackageRailRepository.Line> lines = found.getContent();
    if (lines.isEmpty()) {
      return new Slice(List.of(), found.getTotalElements());
    }
    final Set<Long> ids = new HashSet<>();
    for (final PackageRailRepository.Line line : lines) {
      ids.add(line.getId());
    }
    final Map<Long, Integer> counts = new HashMap<>();
    for (final PackageRailRepository.CountLine c : packages.countDocuments(ids)) {
      counts.put(c.getPackageId(), c.getTotal().intValue());
    }
    // Newest submission of each package: who submitted it and who reviewed it.
    final Map<Long, Long> newest = new HashMap<>();
    final Map<Long, String[]> people = new HashMap<>();
    for (final TaskRailSubmissionRepository.Line s : submissions.findLines(ids)) {
      final Long seen = newest.get(s.getPackageId());
      if (seen == null || s.getSubmissionId() > seen) {
        newest.put(s.getPackageId(), s.getSubmissionId());
        people.put(s.getPackageId(), new String[] {s.getSubmittedBy(), s.getReviewedBy()});
      }
    }
    final List<Seen> out = new ArrayList<>();
    for (final PackageRailRepository.Line line : lines) {
      final String[] who = people.getOrDefault(line.getId(), new String[2]);
      out.add(new Seen(line, counts.getOrDefault(line.getId(), 0), who[0], who[1]));
    }
    return new Slice(out, found.getTotalElements());
  }
}
