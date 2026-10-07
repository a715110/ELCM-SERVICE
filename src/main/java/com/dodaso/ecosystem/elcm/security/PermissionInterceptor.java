package com.dodaso.ecosystem.elcm.security;

import com.dodaso.ecosystem.baseline.common.audit.SecurityContextOrHeaderAuditorAware;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresPermission} on controller methods.
 *
 * <p>Caller identity: the validated SSO access token (see {@link AccessTokenFilter}) when one was
 * sent, which also supplies the roles. Otherwise the {@code X-User-Context} header, which is a
 * trusted-network signal and not proof of identity; set DODASO_AUTHZ_TOKEN_REQUIRED=true to end
 * that fallback.
 *
 * <p>Rollout switch {@code dodaso.authz.enforce} (env {@code DODASO_AUTHZ_ENFORCE}), default
 * false: a denied call is only logged as a warning. Set to true to return 403.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionInterceptor implements HandlerInterceptor {

  private static final String SYSTEM_USER = "system";

  private final CallerRoleService callerRoleService;
  private final SecurityContextOrHeaderAuditorAware callerResolver = new SecurityContextOrHeaderAuditorAware();

  @Value("${dodaso.authz.enforce:false}")
  private boolean enforce;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
    if (required == null) {
      required = method.getBeanType().getAnnotation(RequiresPermission.class);
    }
    if (required == null) {
      return true;
    }

    // A validated SSO token (AccessTokenFilter) is the identity and carries the roles, so no
    // IAMS lookup is needed. Without one, fall back to the X-User-Context header plus IAMS.
    String caller;
    List<String> roles;
    Object verified = request.getAttribute(VerifiedCaller.ATTRIBUTE);
    if (verified instanceof VerifiedCaller vc) {
      caller = vc.loginId();
      roles = vc.roles();
    } else {
      caller = callerResolver.getCurrentAuditor().orElse(SYSTEM_USER);
      roles = SYSTEM_USER.equalsIgnoreCase(caller) ? List.of() : callerRoleService.rolesOf(caller);
    }
    if (ElcmRolePermissions.isGranted(roles, required.value())) {
      return true;
    }

    String detail =
        "caller=" + caller + " roles=" + roles + " permission=" + required.value().getCode()
            + " " + request.getMethod() + " " + request.getRequestURI();
    if (enforce) {
      log.warn("Permission denied: {}", detail);
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permission denied");
    }
    log.warn("Permission would be denied (enforcement off): {}", detail);
    return true;
  }
}
