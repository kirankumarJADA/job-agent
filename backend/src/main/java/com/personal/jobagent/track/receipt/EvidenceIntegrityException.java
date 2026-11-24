package com.personal.jobagent.track.receipt;

/**
 * Stored submission evidence could not be decrypted, or no longer matches the
 * digest the server recorded when the worker reported it. A dedicated type
 * (not {@link IllegalStateException}) so that Spring's repository exception
 * translation does not disguise tampering as a database outage.
 */
public class EvidenceIntegrityException extends RuntimeException {

    public EvidenceIntegrityException(String message) {
        super(message);
    }

    public EvidenceIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
