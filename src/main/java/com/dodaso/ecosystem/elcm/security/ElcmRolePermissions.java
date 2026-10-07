package com.dodaso.ecosystem.elcm.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Default mapping of IAMS role names to ELCM permissions. This is a starting point to be
 * reviewed against the business roles; it is the one place to change until the role and
 * permission editor replaces it with data held in IAMS. Role names are matched case
 * insensitively and exactly as stored in IAMS.
 */
public final class ElcmRolePermissions {

  private static final Map<String, Set<ElcmPermission>> BY_ROLE = new HashMap<>();

  static {
    Set<ElcmPermission> all = EnumSet.allOf(ElcmPermission.class);
    Set<ElcmPermission> viewOnly = EnumSet.of(ElcmPermission.PIPELINE_VIEW);
    Set<ElcmPermission> viewAndSubmit =
        EnumSet.of(ElcmPermission.PIPELINE_VIEW, ElcmPermission.PIPELINE_SUBMIT);

    put("Super Admin", all);
    put("System Admin", all);
    put("Document Submitter", viewAndSubmit);
    put("Business Submitter", viewAndSubmit);
    put("Preparer", viewAndSubmit);
    put("Reviewer", viewOnly);
    put("Approver", viewOnly);
    put("Accountant", viewOnly);
    put("Controller", viewOnly);
    put("Auditor", viewOnly);
  }

  private ElcmRolePermissions() {}

  private static void put(String role, Set<ElcmPermission> permissions) {
    BY_ROLE.put(role.toLowerCase(Locale.ROOT), permissions);
  }

  /** True when any of the given roles grants the permission. Unknown roles grant nothing. */
  public static boolean isGranted(Collection<String> roleNames, ElcmPermission permission) {
    if (roleNames == null) {
      return false;
    }
    for (String role : roleNames) {
      if (role == null) {
        continue;
      }
      Set<ElcmPermission> granted = BY_ROLE.get(role.trim().toLowerCase(Locale.ROOT));
      if (granted != null && granted.contains(permission)) {
        return true;
      }
    }
    return false;
  }
}
