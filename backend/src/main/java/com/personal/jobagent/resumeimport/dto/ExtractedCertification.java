package com.personal.jobagent.resumeimport.dto;

import java.time.LocalDate;

public record ExtractedCertification(
        String name,
        String issuer,
        String issuedOn,
        String expiresOn,
        String credentialId,
        String verificationUrl,
        boolean sourceVerified,
        boolean requiresReview
) {
    public ExtractedCertification(String name, String issuer, LocalDate issuedOn, LocalDate expiresOn,
                                  String credentialId, String verificationUrl,
                                  boolean sourceVerified, boolean requiresReview) {
        this(name, issuer,
                issuedOn != null ? issuedOn.toString().substring(0, 7) : null,
                expiresOn != null ? expiresOn.toString().substring(0, 7) : null,
                credentialId, verificationUrl, sourceVerified, requiresReview);
    }
}
