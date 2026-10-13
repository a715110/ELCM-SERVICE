package com.dodaso.ecosystem.elcm.service.extraction;

import java.util.List;

/** Documents of a package that cannot be read because their contract type has no usable extraction template. */
public record ExtractionCoverage(List<String> withoutTemplate) {
}
