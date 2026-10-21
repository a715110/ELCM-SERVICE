package com.dodaso.ecosystem.elcm.activity;

import java.util.List;

/** ADDED 2026-10-20 -- one page of a package's Activity Log, newest first. Pages start at 0. */
public record ActivityPage(List<ActivityEntry> entries, int page, int size, long total,
                           int totalPages) {}
