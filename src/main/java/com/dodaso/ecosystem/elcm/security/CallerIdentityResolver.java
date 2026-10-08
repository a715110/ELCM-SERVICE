package com.dodaso.ecosystem.elcm.security;

import com.dodaso.ecosystem.baseline.common.audit.SecurityContextOrHeaderAuditorAware;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves the login id of the caller of the current request, the same way
 * {@link PermissionInterceptor} does: the validated SSO token when one was sent, otherwise the
 * X-User-Context header (the same identity the audit columns record), otherwise "system".
 * For business rules that depend on who is calling, such as "own documents only".
 */
@Component
public class CallerIdentityResolver {

  private static final String SYSTEM_USER = "system";

  private final SecurityContextOrHeaderAuditorAware auditorAware =
      new SecurityContextOrHeaderAuditorAware();

  public String loginIdOf(HttpServletRequest request) {
    Object verified = request.getAttribute(VerifiedCaller.ATTRIBUTE);
    if (verified instanceof VerifiedCaller vc && vc.loginId() != null && !vc.loginId().isBlank()) {
      return vc.loginId();
    }
    return auditorAware.getCurrentAuditor().orElse(SYSTEM_USER);
  }
}
