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
 * <p>Caller identity is the Security context user or the {@code X-User-Context} header sent by
 * elcm-ui. Note: elcm-service does not yet validate the OAuth2 token, so that header is a
 * trusted-network signal, not proof of identity. Real enforcement needs token validation at the
 * service (resource server); this check is the in-code half that stays valid afterwards.
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

    String caller = callerResolver.getCurrentAuditor().orElse(SYSTEM_USER);
    List<String> roles =
        SYSTEM_USER.equalsIgnoreCase(caller) ? List.of() : callerRoleService.rolesOf(caller);
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
