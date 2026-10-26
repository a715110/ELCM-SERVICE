package com.dodaso.ecosystem.elcm.packagedetail;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADDED 2026-10-25 -- the database side of Package Details: one read-only transaction, no outbound
 * calls. Display names are added later by {@link PackageDetailService}.
 */
@Service
@RequiredArgsConstructor
public class PackageDetailDataService {

  private final PackageDetailRepository repository;

  /** The header and the submissions of a package, or empty when there is no such package. */
  public record Read(PackageDetailRepository.Header header,
                     List<PackageDetailRepository.SubmissionLine> submissions,
                     boolean involved) {
  }

  @Transactional(readOnly = true)
  public Optional<Read> read(final Long id, final String login) {
    final Optional<PackageDetailRepository.Header> header = repository.findHeader(id);
    if (header.isEmpty()) {
      return Optional.empty();
    }
    final String key = login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
    final boolean involved = !key.isEmpty() && repository.countInvolved(id, key) > 0;
    return Optional.of(new Read(header.get(), repository.findSubmissions(id), involved));
  }

  /** True when the package exists and the person is involved in it ("Mine"). */
  @Transactional(readOnly = true)
  public boolean isInvolved(final Long id, final String login) {
    final String key = login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
    return !key.isEmpty() && repository.countInvolved(id, key) > 0;
  }

  /** True when a package with this id exists. */
  @Transactional(readOnly = true)
  public boolean exists(final Long id) {
    return repository.findHeader(id).isPresent();
  }
}
