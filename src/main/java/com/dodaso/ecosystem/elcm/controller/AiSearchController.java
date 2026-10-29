package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.integration.ai.ElcmAiSearchRequest;
import com.dodaso.ecosystem.elcm.integration.ai.ElcmAiSearchResult;
import com.dodaso.ecosystem.elcm.integration.ai.ElcmAiSearchService;
import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADDED 2026-10-28 -- AI search over ELCM documents and contract records. The workspaces searched
 * are decided by ElcmAiSearchService, never taken from the browser beyond narrowing to one of
 * them. 400 for an empty search, 403 for a workspace the caller may not see, 503 when AI search
 * is down.
 */
@RestController
@RequestMapping("/api/v1/ai-search")
@RequiredArgsConstructor
public class AiSearchController {

    private final ElcmAiSearchService elcmAiSearchService;
    private final CallerIdentityResolver callerIdentityResolver;

    @PostMapping
    @RequiresPermission(ElcmPermission.PIPELINE_VIEW)
    public List<ElcmAiSearchResult> search(@RequestBody final ElcmAiSearchRequest request,
                                           final HttpServletRequest httpRequest) {
        return elcmAiSearchService.search(request, callerIdentityResolver.loginIdOf(httpRequest));
    }
}
