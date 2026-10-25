package com.dodaso.ecosystem.elcm.integration.address;

import java.util.Locale;

/** ADDED 2026-10-25 -- compares two address parts ignoring case, spaces and punctuation. */
final class AddressCompare {

  private AddressCompare() {
  }

  static boolean same(final String typed, final String found) {
    return normal(typed).equals(normal(found));
  }

  static String normal(final String value) {
    return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
  }
}
