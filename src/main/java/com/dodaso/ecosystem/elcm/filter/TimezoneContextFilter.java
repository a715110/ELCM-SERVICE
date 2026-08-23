package com.dodaso.ecosystem.elcm.filter;

import com.dodaso.ecosystem.elcm.service.handler.TimezoneContextHelper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class TimezoneContextFilter implements Filter {

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    try {
      if (request instanceof HttpServletRequest httpRequest) {
        String userTimezone = httpRequest.getHeader("X-User-Timezone");
        if (userTimezone != null && !userTimezone.trim().isEmpty()) {
          TimezoneContextHelper.setUserTimezone(userTimezone);
        } else {
          // Fallback to EST or system default
          TimezoneContextHelper.setUserTimezone("America/New_York");
        }
      }
      chain.doFilter(request, response);
    } finally {
      TimezoneContextHelper.clear();
    }
  }
}