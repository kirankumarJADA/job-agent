package com.personal.jobagent.track.receipt;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an ATS submission receipt violates any of the 7 genuine receipt
 * invariants, contains mock or synthetic prefixes in production paths, or fails
 * candidate sandbox authorization.
 *
 * <p>Annotated with {@link HttpStatus#UNPROCESSABLE_ENTITY} (422) to enforce the
 * mandate that production code paths MUST reject mock prefixes (MOCK-*, TEST-*, SAMPLE-*)
 * and invalid receipts with 422 Unprocessable Entity or InvalidReceiptException.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class InvalidReceiptException extends RuntimeException {

    private final String reasonCode;

    public InvalidReceiptException(String message) {
        super(message);
        this.reasonCode = "INVALID_RECEIPT";
    }

    public InvalidReceiptException(String message, String reasonCode) {
        super(message);
        this.reasonCode = reasonCode != null ? reasonCode : "INVALID_RECEIPT";
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
