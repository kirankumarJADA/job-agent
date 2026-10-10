package com.personal.jobagent.profile;

/** The account holder's own name and sign-in email, as stored on {@code users}. */
public record ContactRecord(String displayName, String email) {
}
