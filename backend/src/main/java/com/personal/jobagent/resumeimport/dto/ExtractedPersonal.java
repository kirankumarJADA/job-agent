package com.personal.jobagent.resumeimport.dto;

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
        boolean sourceVerified
) {
}
