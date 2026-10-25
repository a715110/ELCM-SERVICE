package com.dodaso.ecosystem.elcm.integration.address;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ADDED 2026-10-25 -- the answer of an address check. The status is one of {@link #VERIFIED}
 * (found, same as typed), {@link #SUGGESTED} (found, but the standard form differs: the line1,
 * line2, city, state and zip fields hold it), {@link #NOT_FOUND} (the address was not matched) and
 * {@link #UNAVAILABLE} (the check could not be done: not set up, limit reached or the service is
 * down). The message is plain text for the user. None of the states blocks saving.
 */
@Getter
@Setter
@NoArgsConstructor
public class AddressVerifyResult {

  public static final String VERIFIED = "VERIFIED";
  public static final String SUGGESTED = "SUGGESTED";
  public static final String NOT_FOUND = "NOT_FOUND";
  public static final String UNAVAILABLE = "UNAVAILABLE";

  private String status;
  private String message;
  private String line1;
  private String line2;
  private String city;
  private String state;
  private String zip;

  /** The four digits after the ZIP, when the provider knows them. */
  private String zipPlus4;

  /** Which provider answered, for example USPS. */
  private String provider;

  public static AddressVerifyResult of(final String status, final String message,
      final String provider) {
    final AddressVerifyResult result = new AddressVerifyResult();
    result.setStatus(status);
    result.setMessage(message);
    result.setProvider(provider);
    return result;
  }
}
