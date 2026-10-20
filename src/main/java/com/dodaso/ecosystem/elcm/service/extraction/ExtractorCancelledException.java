package com.dodaso.ecosystem.elcm.service.extraction;

/**
 * ADDED 2026-10-18 -- the extractor call was given up on purpose because the package was stopped.
 * Not a failure of the extractor, so the worker does not count it as an attempt or a failed job.
 */
public class ExtractorCancelledException extends ExtractorException {

  private static final long serialVersionUID = 1L;

  public ExtractorCancelledException() {
    super("The extraction was stopped");
  }
}
