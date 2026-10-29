package com.dodaso.ecosystem.elcm.integration.ai;

import java.util.List;

/**
 * AI search from an ELCM screen. workspaceId limits the search to one workspace; null searches
 * every workspace the caller may see. sourceTypes optionally limits the kinds of results
 * (ElcmSourceTypeEnum values).
 */
public record ElcmAiSearchRequest(String query, Long workspaceId, List<String> sourceTypes, Integer limit) {
}
