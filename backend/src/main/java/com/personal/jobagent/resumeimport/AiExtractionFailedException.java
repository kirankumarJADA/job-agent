package com.personal.jobagent.resumeimport;

/** The AI provider could not be reached, or its response was not usable structured data. */
public class AiExtractionFailedException extends RuntimeException {
    public AiExtractionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
