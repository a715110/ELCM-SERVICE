package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import com.dodaso.ecosystem.elcm.entity.pipeline.PackageDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.StagedDocument;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.StagedDocumentRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds earlier uploads with the same content in the same workspace (ADDED 2026-10-14). Only active
 * staged documents count (a deleted one is hidden by the entity filter), and rows uploaded before
 * the hash existed have no hash and never match. The answer is advisory: nothing is blocked.
 */
@Service
@RequiredArgsConstructor
public class DuplicateCheckService {

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
  private static final int MAX_HASHES = 100;

  private final StagedDocumentRepository stagedDocumentRepository;
  private final PackageDocumentRepository packageDocumentRepository;
  private final FileUploadLookupService fileUploadLookupService;

  /** Lower-case, trimmed; null when it is not a SHA-256 in hex. */
  public static String cleanHash(final String hash) {
    if (hash == null) {
      return null;
    }
    final String h = hash.trim().toLowerCase(Locale.ROOT);
    return SHA256_HEX.matcher(h).matches() ? h : null;
  }

  /** One match per hash (the newest upload); hashes without a match are left out. */
  @Transactional(readOnly = true)
  public List<DuplicateMatch> find(final DuplicateCheckRequest request) {
    if (request == null
        || request.workspaceCode() == null
        || request.hashes() == null
        || request.hashes().isEmpty()) {
      return List.of();
    }
    final Set<String> hashes = new HashSet<>();
    for (final String raw : request.hashes()) {
      final String h = cleanHash(raw);
      if (h != null && hashes.size() < MAX_HASHES) {
        hashes.add(h);
      }
    }
    if (hashes.isEmpty()) {
      return List.of();
    }
    final Map<String, StagedDocument> newestByHash = new LinkedHashMap<>();
    for (final StagedDocument doc :
        stagedDocumentRepository.findByWorkspace_CodeAndContentHashInOrderByIdDesc(
            request.workspaceCode(), hashes)) {
      newestByHash.putIfAbsent(doc.getContentHash(), doc);
    }
    if (newestByHash.isEmpty()) {
      return List.of();
    }
    final Set<Long> docIds = new HashSet<>();
    final Set<Long> fileIds = new HashSet<>();
    for (final StagedDocument doc : newestByHash.values()) {
      docIds.add(doc.getId());
      fileIds.add(doc.getFileUploadId());
    }
    final Map<Long, String> packageByDoc = new HashMap<>();
    for (final PackageDocument pd : packageDocumentRepository.findByStagedDocument_IdIn(docIds)) {
      packageByDoc.put(pd.getStagedDocument().getId(), pd.getContractPackage().getPackageCode());
    }
    final Map<Long, FileUploadDTO> files = fileUploadLookupService.findByIds(fileIds);
    final List<DuplicateMatch> matches = new ArrayList<>();
    for (final Map.Entry<String, StagedDocument> e : newestByHash.entrySet()) {
      final StagedDocument doc = e.getValue();
      final FileUploadDTO file = files.get(doc.getFileUploadId());
      matches.add(
          new DuplicateMatch(
              e.getKey(),
              doc.getId(),
              file != null ? file.getFileName() : null,
              doc.getUploadedBy(),
              doc.getUploadedAt() == null ? null : doc.getUploadedAt().toString(),
              packageByDoc.get(doc.getId())));
    }
    return matches;
  }
}
