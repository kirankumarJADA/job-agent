package com.personal.jobagent.resumeimport.dto;

import java.util.Map;

/**
 * Section 5 "Personal information". {@code email} is shown for review only —
 * the import never overwrites the authenticated account's own identity/email.
 */
public record ExtractedPersonal(
        String fullName,
        String headline,
        String email,
        String phone,
        String location,
        String linkedinUrl,
        String websiteUrl,
        boolean sourceVerified,
        Map<String, Boolean> fieldVerification
) {
    public ExtractedPersonal(String fullName, String headline, String email, String phone,
                             String location, String linkedinUrl, String websiteUrl,
                             boolean sourceVerified) {
        this(fullName, headline, email, phone, location, linkedinUrl, websiteUrl, sourceVerified, Map.of());
    }

    public boolean isFieldVerified(String field) {
        if (fieldVerification != null && fieldVerification.containsKey(field)) {
            return Boolean.TRUE.equals(fieldVerification.get(field));
        }
        return sourceVerified;
    }
}
