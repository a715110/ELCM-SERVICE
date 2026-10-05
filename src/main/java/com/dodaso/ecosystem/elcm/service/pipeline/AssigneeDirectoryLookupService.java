package com.dodaso.ecosystem.elcm.service.pipeline;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import com.dodaso.ecosystem.auth.constant.UserControllerAPIEnum;
import com.dodaso.ecosystem.auth.container.UserDirectoryDTOContainer;
import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.container.RESTReqContainer;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves a staged_document's assignee_id against IAMS's directory, so the
 * dashboard's Assignee column can show the person's display name and its
 * hover preview can show team/role/workspace-specialty details.
 *
 * REVISED 2026-10-04 (assigneeId -> loginId): staged_document.assignee_id now
 * holds the assignee's IAMS login ID (what the Upload Files "Assign To"
 * autocomplete submits -- see elcm-ui's UploadFilesBean.assignToUser), not a
 * display name. A login ID is unique, so two people who share a display name
 * are now distinct assignments. Resolution order in findByAssigneeId():
 *   1. exact login ID match (case-insensitive -- login IDs are email-style
 *      and IAMS does not guarantee casing);
 *   2. LEGACY FALLBACK: display-name match, for rows written before this
 *      change when assignee_id held a display name. Same first-wins-on-
 *      duplicates behavior the old display-name-only lookup had -- that
 *      ambiguity can't be repaired retroactively, only avoided going
 *      forward. A name collision can only ever resolve through this path,
 *      never through step 1.
 * No data migration is required for the fallback to work; legacy rows
 * simply keep resolving by name until they are re-assigned or backfilled.
 *
 * WHY A FULL-LIST CACHE, NOT PER-ASSIGNEE LIKE FileUploadLookupService:
 * IAMS has getUserDirectoryByLoginId() and getUserDirectories() (the whole
 * active roster), but no lookup that would also serve the legacy
 * display-name fallback. With only ~14 directory entries today, fetching the
 * whole roster once and indexing it in memory is simpler and cheaper than N
 * lookups, and is what elcm-ui's UploadFilesService.findAssignableUsers()
 * already does for the dropdown. Revisit if the directory grows large enough
 * for a full fetch per cache miss to be a real cost -- see
 * UserDirectoryService.getAllUserDirectories()'s own Javadoc on the IAMS
 * side for the matching caveat. The cached value is a pair of prebuilt
 * indexes (DirectoryIndex), so a dashboard render does O(1) map lookups per
 * row rather than rescanning the roster.
 *
 * Fails open (empty Optional) on any IAMS error or unresolved value, same
 * reasoning as FileUploadLookupService: one directory lookup failing
 * shouldn't break the whole dashboard render. An unresolved assignee (e.g. a
 * deactivated user no longer in the active roster) is shown by its raw
 * stored value instead -- see StageDocumentService.mapToRow().
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssigneeDirectoryLookupService {

    private static final String CACHE_NAME = "elcm";
    private static final String CACHE_KEY = "iamsAssigneeDirectoryIndex";

    private final RESTServiceClient restServiceClient;
    private final CacheManager elcmCacheManager;

    /**
     * @param assigneeId the raw staged_document.assignee_id -- a login ID, or
     *                   (legacy rows) a display name.
     */
    public Optional<UserDirectoryDTO> findByAssigneeId(final String assigneeId) {
        if (assigneeId == null || assigneeId.isBlank()) {
            return Optional.empty();
        }
        final DirectoryIndex index = loadIndex();
        final UserDirectoryDTO byLoginId = index.byLoginId().get(normalizeLoginId(assigneeId));
        if (byLoginId != null) {
            return Optional.of(byLoginId);
        }
        return Optional.ofNullable(index.byDisplayName().get(assigneeId));
    }

    private static String normalizeLoginId(final String loginId) {
        return loginId.trim().toLowerCase(Locale.ROOT);
    }

    private DirectoryIndex loadIndex() {
        final Cache cache = elcmCacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            final DirectoryIndex cached = cache.get(CACHE_KEY, DirectoryIndex.class);
            if (cached != null) {
                return cached;
            }
        }

        final DirectoryIndex index = fetchIndex();
        // Failure (empty index) is cached too, same as before this revision:
        // the 30-minute TTL bounds how long an IAMS outage is remembered.
        if (cache != null) {
            cache.put(CACHE_KEY, index);
        }
        return index;
    }

    private DirectoryIndex fetchIndex() {
        try {
            final RESTReqContainer<UserDirectoryDTOContainer> restReqContainer = new RESTReqContainer<>(
                    ServiceDiscoveryEnum.iams_service.getServiceDiscoveryName(),
                    UserControllerAPIEnum.userControllerAPIEnum_getUserDirectories.getEndPoint(),
                    new UserDirectoryDTOContainer(),
                    new ParameterizedTypeReference<>() {
                    },
                    HttpMethod.GET);

            final UserDirectoryDTOContainer response = restServiceClient.callRESTService(restReqContainer);
            if (response == null || response.getUserDirectoryDTOList() == null) {
                log.warn("IAMS returned no directory entries from getUserDirectories()");
                return DirectoryIndex.EMPTY;
            }

            final Map<String, UserDirectoryDTO> byLoginId = new HashMap<>();
            final Map<String, UserDirectoryDTO> byDisplayName = new HashMap<>();
            for (final UserDirectoryDTO entry : response.getUserDirectoryDTOList()) {
                if (entry.getLoginId() != null && !entry.getLoginId().isBlank()) {
                    byLoginId.putIfAbsent(normalizeLoginId(entry.getLoginId()), entry);
                }
                if (entry.getDisplayName() != null && !entry.getDisplayName().isBlank()) {
                    // First entry wins on a duplicate display name -- legacy
                    // fallback only; see class Javadoc.
                    byDisplayName.putIfAbsent(entry.getDisplayName(), entry);
                }
            }
            return new DirectoryIndex(Map.copyOf(byLoginId), Map.copyOf(byDisplayName));
        } catch (final Exception e) {
            // Same reasoning as FileUploadLookupService.fetchChunk(): a
            // directory outage shouldn't take down the whole dashboard
            // render -- log and return an empty index (every assignee falls
            // back to its raw value and the "no preview" tooltip variant)
            // rather than propagating.
            log.error("Failed to fetch IAMS user directory for assignee lookup", e);
            return DirectoryIndex.EMPTY;
        }
    }

    /** Immutable pair of lookup indexes built from one roster fetch. */
    private record DirectoryIndex(Map<String, UserDirectoryDTO> byLoginId,
                                  Map<String, UserDirectoryDTO> byDisplayName) {
        static final DirectoryIndex EMPTY = new DirectoryIndex(Map.of(), Map.of());
    }
}
