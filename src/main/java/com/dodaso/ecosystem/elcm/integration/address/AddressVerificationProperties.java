package com.dodaso.ecosystem.elcm.integration.address;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ADDED 2026-10-25 -- settings of the address check, bound from {@code elcm.address-verification.*}.
 * The USPS keys come from the environment (USPS_CLIENT_ID, USPS_CLIENT_SECRET) and are never
 * written to a file that goes to GitHub.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "elcm.address-verification")
public class AddressVerificationProperties {

  /** Master switch. When false the button answers "not available". */
  private boolean enabled = true;

  /** Which provider answers: geocodio (the demo choice) or usps. */
  private String provider = "geocodio";

  /** USPS API root. */
  private String uspsBaseUrl = "https://apis.usps.com";

  private String uspsClientId;

  private String uspsClientSecret;

  /** Geocodio API root (the version is part of it) and the key, from the environment. */
  private String geocodioBaseUrl = "https://api.geocod.io/v2";

  private String geocodioApiKey;

  /** Seconds to wait for the provider. */
  private int timeoutSeconds = 8;
}
