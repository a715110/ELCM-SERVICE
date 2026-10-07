package com.dodaso.ecosystem.elcm.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the SSO access token that elcm-ui forwards in {@code X-Access-Token}: signature
 * (against the SSO public keys), issuer and expiry. A valid token becomes the caller identity:
 * the Security context holds it (so audit columns use the token subject), and the caller with
 * the token's {@code roles} claim is stored for {@link PermissionInterceptor}.
 *
 * <ul>
 *   <li>Token present but invalid or expired: always 401.
 *   <li>Token absent: 401 when {@code required}; otherwise the request passes and identity falls
 *       back to the X-User-Context header (rollout mode, logged).
 * </ul>
 * Applies to /api/** only. Not a Spring bean: it is added to the security filter chain in
 * {@link com.dodaso.ecosystem.elcm.config.ElcmSecurityConfig}, after the context is set up.
 */
public class AccessTokenFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Access-Token";
  private static final Logger log = LoggerFactory.getLogger(AccessTokenFilter.class);

  private final JwtDecoder decoder;
  private final boolean required;

  public AccessTokenFilter(JwtDecoder decoder, boolean required) {
    this.decoder = decoder;
    this.required = required;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path == null || !path.startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String raw = request.getHeader(HEADER);
    if (raw == null || raw.isBlank()) {
      if (required) {
        reject(response, "Access token required");
        return;
      }
      log.debug("No access token on {} {}, falling back to X-User-Context", request.getMethod(),
          request.getRequestURI());
      chain.doFilter(request, response);
      return;
    }

    Jwt jwt;
    try {
      jwt = decoder.decode(raw.trim());
    } catch (JwtException e) {
      log.warn("Rejected access token on {} {}: {}", request.getMethod(), request.getRequestURI(),
          e.getMessage());
      reject(response, "Invalid access token");
      return;
    }
    String login = jwt.getSubject();
    if (login == null || login.isBlank()) {
      reject(response, "Access token has no subject");
      return;
    }
    List<String> roles = jwt.getClaimAsStringList("roles");
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new JwtAuthenticationToken(jwt, List.of(), login));
    SecurityContextHolder.setContext(context);
    request.setAttribute(
        VerifiedCaller.ATTRIBUTE, new VerifiedCaller(login, roles == null ? List.of() : roles));
    chain.doFilter(request, response);
  }

  private static void reject(HttpServletResponse response, String message) throws IOException {
    response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, message);
  }
}
