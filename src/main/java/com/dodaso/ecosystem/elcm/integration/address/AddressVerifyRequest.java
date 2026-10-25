package com.dodaso.ecosystem.elcm.integration.address;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** ADDED 2026-10-25 -- the address as the user typed it, sent to the address check. */
@Getter
@Setter
@NoArgsConstructor
public class AddressVerifyRequest {

  private String line1;
  private String line2;
  private String city;
  private String state;
  private String zip;
}
