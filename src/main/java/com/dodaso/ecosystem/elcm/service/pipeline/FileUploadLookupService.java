package com.dodaso.ecosystem.elcm.service.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.container.RESTReqContainer;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.common.container.FileUploadDTOContainer;
import com.dodaso.ecosystem.common.dto.FileUploadDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves file_upload metadata (name, content type, size, etc.) for a set
 * of file_upload_id values, batching and caching the calls to common-
 * service's FileStorageController.batch() endpoint -- built for
 * StageDocumentService.findStaged() (see that class), and reusable by any
 * future elcm-service screen that needs the same lookup.
 *
 * WHY NOT @Cacheable: Spring's declarative caching keys a method's cached
 * result on its whole argument -- for a Set<Long> parameter, that means
 * two calls with different id sets NEVER share a cache entry, even if
 * every individual id in both sets was already cached from some earlier
 * call. That defeats the point of caching per-file metadata across
 * different screens/renders. This class instead does manual per-id
 * Cache.get()/Cache.put() against the SAME "elcm" Caffeine region
 * WorkspaceService.getAllWorkspaces() already uses (via elcmCacheManager,
 * 30-minute TTL / 500-entry cap, configured in CacheConfig) -- each id
 * gets its own cache entry, so a batch request only ever pays the network
 * cost for ids that are genuinely not yet cached.
 *
 * CHUNKING: common-service's /batch endpoint expects callers to cap batch
 * size themselves (confirmed: 20). Only the ids that MISS the cache are
 * chunked and sent -- a request for 50 ids where 45 are already cached
 * results in one chunk of 5, not three chunks of 20/20/10.
 *
 * MISSING IDS: an id that never resolves (not found, or soft-deleted on
 * the common-service side) is simply absent from the returned map --
 * never cached as a negative/empty result, and never surfaced as an
 * error. Callers (StageDocumentService) are expected to treat "no entry
 * for this id" as "exclude this row" (confirmed), not retry or fail.
 * Deliberately NOT caching misses: a file that's mid-upload when first
 * queried and resolves moments later shouldn't stay invisible for the
 * rest of the 30-minute TTL window.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileUploadLookupService {

    private static final String CACHE_NAME = "elcm";
    private static final String CACHE_KEY_PREFIX = "fileUpload:";
    private static final int BATCH_SIZE = 20;

    private final RESTServiceClient restServiceClient;
    private final CacheManager elcmCacheManager;

    /**
     * Returns a map of fileUploadId -> FileUploadDTO for every id in
     * `fileUploadIds` that resolved to an active file_upload row. Ids with
     * no entry in the returned map did not resolve; see class Javadoc.
     */
    public Map<Long, FileUploadDTO> findByIds(final Set<Long> fileUploadIds) {
        if (fileUploadIds == null || fileUploadIds.isEmpty()) {
            return Map.of();
        }

        final Cache cache = elcmCacheManager.getCache(CACHE_NAME);
        final Map<Long, FileUploadDTO> result = new HashMap<>();
        final Set<Long> missing = new HashSet<>();

        for (final Long id : fileUploadIds) {
            final FileUploadDTO cached = cache != null ? cache.get(CACHE_KEY_PREFIX + id, FileUploadDTO.class) : null;
            if (cached != null) {
                result.put(id, cached);
            } else {
                missing.add(id);
            }
        }

        if (!missing.isEmpty()) {
            for (final List<Long> chunk : chunk(missing, BATCH_SIZE)) {
                final List<FileUploadDTO> fetched = fetchChunk(chunk);
                for (final FileUploadDTO dto : fetched) {
                    result.put(dto.getId(), dto);
                    if (cache != null) {
                        cache.put(CACHE_KEY_PREFIX + dto.getId(), dto);
                    }
                }
            }
        }

        return result;
    }

    private List<FileUploadDTO> fetchChunk(final List<Long> ids) {
        try {
            final FileUploadDTOContainer requestContainer = new FileUploadDTOContainer();
            requestContainer.setFileUploadIds(new HashSet<>(ids));

            final RESTReqContainer<FileUploadDTOContainer> restReqContainer = new RESTReqContainer<>(
                    ServiceDiscoveryEnum.common_service.getServiceDiscoveryName(),
                    "/api/v1/files/batch",
                    requestContainer,
                    new ParameterizedTypeReference<>() {
                    },
                    HttpMethod.POST);

            final FileUploadDTOContainer response = restServiceClient.callRESTService(restReqContainer);
            if (response == null || response.getFileUploadDTOList() == null) {
                log.warn("common-service returned no file_upload metadata for {} id(s): {}", ids.size(), ids);
                return List.of();
            }
            return response.getFileUploadDTOList();
        } catch (final Exception e) {
            // A batch failing shouldn't take down the whole grid render --
            // log and treat this chunk's ids as unresolved (excluded from
            // the response, same as a genuinely missing id) rather than
            // propagating and failing StageDocumentService.findStaged()
            // entirely over a transient common-service/network issue.
            log.error("Failed to fetch file_upload metadata for {} id(s): {}", ids.size(), ids, e);
            return List.of();
        }
    }

    private static List<List<Long>> chunk(final Set<Long> ids, final int size) {
        final List<Long> all = new ArrayList<>(ids);
        final List<List<Long>> chunks = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            chunks.add(all.subList(i, Math.min(i + size, all.size())));
        }
        return chunks;
    }
}
