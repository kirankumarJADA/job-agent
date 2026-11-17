package com.personal.jobagent.resumeimport.dto;

import java.time.LocalDate;
import java.util.UUID;

public record CertificationDecision(
        ImportAction action,
        UUID matchedExistingId,
        String name,
        String issuer,
        LocalDate issuedOn,
        String credentialId
) {
}
