package com.personal.jobagent.resumeimport.dto;

import java.time.LocalDate;

public record ExtractedCertification(
        String name,
        String issuer,
        LocalDate issuedOn,
        LocalDate expiresOn,
        String credentialId,
        String verificationUrl,
        boolean sourceVerified,
        boolean requiresReview
) {
}
