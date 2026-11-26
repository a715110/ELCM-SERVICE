package com.dodaso.ecosystem.elcm.integration.address;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ADDED 2026-10-25 -- settings of the address check, bound from {@code elcm.address-verification.*}.
 * The Geocodio key comes from the environment (GEOCODIO_API_KEY) and is never written to a file
 * that goes to GitHub. REVISED 2026-11-24: the unused USPS settings were removed.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "elcm.address-verification")
public class AddressVerificationProperties {

  /** Master switch. When false the button answers "not available". */
  private boolean enabled = true;

  /** Which provider answers. Only geocodio exists now; another one is added as a new provider. */
  private String provider = "geocodio";

  /** Geocodio API root (the version is part of it) and the key, from the environment. */
  private String geocodioBaseUrl = "https://api.geocod.io/v2";

  private String geocodioApiKey;

  /** Seconds to wait for the provider. */
  private int timeoutSeconds = 8;
}
