package com.dodaso.ecosystem.elcm.security;

import java.util.List;

/** Identity and roles taken from a validated SSO access token. */
public record VerifiedCaller(String loginId, List<String> roles) {

  /** Request attribute under which {@link AccessTokenFilter} stores the caller. */
  public static final String ATTRIBUTE = VerifiedCaller.class.getName();
}
