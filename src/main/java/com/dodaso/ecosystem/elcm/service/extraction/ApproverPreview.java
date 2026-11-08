package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import java.util.List;

/**
 * ADDED 2026-11-07 -- what the Complete review dialog shows: the login id of the approver ELCM
 * would pick (null when nobody qualifies) and everyone the reviewer may choose instead.
 */
public record ApproverPreview(String suggested, List<UserDirectoryDTO> candidates) {
}
