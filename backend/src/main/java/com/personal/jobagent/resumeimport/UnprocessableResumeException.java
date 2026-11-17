package com.personal.jobagent.resumeimport;

/**
 * The upload was read but cannot be used: wrong type, corrupt, encrypted,
 * too large, scanned with no extractable text, or empty. The message is
 * always safe to show the caller as-is (no file content, no stack trace).
 */
public class UnprocessableResumeException extends RuntimeException {
    public UnprocessableResumeException(String message) {
        super(message);
    }
}
