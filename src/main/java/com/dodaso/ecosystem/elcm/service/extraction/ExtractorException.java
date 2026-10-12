package com.dodaso.ecosystem.elcm.service.extraction;

/** The extractor could not be reached, timed out, or answered with an error. */
public class ExtractorException extends Exception {

    private static final long serialVersionUID = 1L;

    public ExtractorException(final String message) {
        super(message);
    }

    public ExtractorException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
