package com.dodaso.ecosystem.elcm.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@Slf4j
public class HeaderBasedAuditorAware implements AuditorAware<String> {

  @Override
  public Optional<String> getCurrentAuditor() {
    try {
      RequestAttributes requestAttributes = RequestContextHolder.currentRequestAttributes();
      if (requestAttributes instanceof ServletRequestAttributes servletRequestAttributes) {
        HttpServletRequest request = servletRequestAttributes.getRequest();

        // Get user from header sent by JSF application
        String user = request.getHeader("X-User-Context");
        if (user != null && !user.trim().isEmpty()) {
          log.debug("Found user from X-User-Context header: {}", user);
          return Optional.of(user);
        }

        // Fallback to other headers if needed
        String userFromRemote = request.getHeader("X-Remote-User");
        if (userFromRemote != null && !userFromRemote.trim().isEmpty()) {
          log.debug("Found user from X-Remote-User header: {}", userFromRemote);
          return Optional.of(userFromRemote);
        }

        // Log available headers for debugging (only in debug mode)
        if (log.isDebugEnabled()) {
          log.debug("No user found in headers. Available headers: {}",
              Collections.list(request.getHeaderNames()));
        }
      }
    } catch (IllegalStateException e) {
      // No request context available (async processing, scheduled tasks, etc.)
      log.debug("No request context available, using SYSTEM user");
    }

    return Optional.of("SYSTEM");
  }
}