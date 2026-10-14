package com.dodaso.ecosystem.elcm.service.pipeline;

import java.util.List;

/** Hashes (SHA-256, hex) to look for in one workspace. */
public record DuplicateCheckRequest(String workspaceCode, List<String> hashes) {}
