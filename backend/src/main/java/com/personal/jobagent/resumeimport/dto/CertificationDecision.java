package com.personal.jobagent.resumeimport.dto;

import java.time.LocalDate;
import java.util.UUID;

public record CertificationDecision(
        ImportAction action,
        UUID matchedExistingId,
        String name,
        String issuer,
        String issuedOn,
        String credentialId
) {
    public CertificationDecision(ImportAction action, UUID matchedExistingId, String name, String issuer,
                                 LocalDate issuedOn, String credentialId) {
        this(action, matchedExistingId, name, issuer,
                issuedOn != null ? issuedOn.toString().substring(0, 7) : null,
                credentialId);
    }
}
