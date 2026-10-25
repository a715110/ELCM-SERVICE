package com.dodaso.ecosystem.elcm.integration.address;

/**
 * ADDED 2026-10-25 -- one address check service behind the Verify address button. The demo uses
 * USPS ({@link UspsAddressVerificationProvider}); a paid service with type-ahead and non US
 * addresses is added later as another implementation, chosen with elcm.address-verification.provider.
 * An implementation never throws for a normal "not found" or "not available" case: it returns the
 * matching {@link AddressVerifyResult} status.
 */
public interface AddressVerificationProvider {

  /** The value of elcm.address-verification.provider that selects this provider. */
  String name();

  /** True when the provider has what it needs (for example its keys) to answer. */
  boolean isConfigured();

  /** Checks an address that already passed the basic input check. */
  AddressVerifyResult verify(AddressVerifyRequest request);
}
