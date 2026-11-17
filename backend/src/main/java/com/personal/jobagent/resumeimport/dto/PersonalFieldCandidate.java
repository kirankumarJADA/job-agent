package com.personal.jobagent.resumeimport.dto;

/** One personal-info field, extracted value next to what the profile already has, for a per-field accept/edit/reject decision. */
public record PersonalFieldCandidate(String field, String extractedValue, String currentValue, boolean sourceVerified) {
}
