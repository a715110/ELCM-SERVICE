package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The submitter's own view (ADDED 2026-10-14): the submissions they made and the documents of those
 * that were applied. Database work in {@link MySubmissionsDataService}; the file names come from
 * common-service afterwards, outside any transaction.
 */
@Service
@RequiredArgsConstructor
public class MySubmissionsService {

  private static final String FILE_UNAVAILABLE = "(file unavailable)";

  private final MySubmissionsDataService dataService;
  private final FileUploadLookupService fileUploadLookupService;

  public List<MySubmissionItem> findSubmissions(final String login) {
    return dataService.loadSubmissions(login);
  }

  public List<MyCommittedDocument> findCommitted(final String login) {
    final List<MyCommittedDocument> docs = dataService.loadCommitted(login);
    final Set<Long> ids = new HashSet<>();
    for (final MyCommittedDocument d : docs) {
      if (d.getFileUploadId() != null) {
        ids.add(d.getFileUploadId());
      }
    }
    final Map<Long, FileUploadDTO> files = fileUploadLookupService.findByIds(ids);
    for (final MyCommittedDocument d : docs) {
      final FileUploadDTO file =
          d.getFileUploadId() == null ? null : files.get(d.getFileUploadId());
      d.setFileName(
          file != null && file.getFileName() != null ? file.getFileName() : FILE_UNAVAILABLE);
    }
    return docs;
  }
}
