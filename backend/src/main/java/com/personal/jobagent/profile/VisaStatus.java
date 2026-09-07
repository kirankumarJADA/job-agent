package com.personal.jobagent.profile;

/**
 * Resolves the open PHASE1-BLUEPRINT.md decision on work_eligibility's
 * visaStatus values. Stored inside the profiles.work_eligibility jsonb
 * blob (V001) — jsonb has no CHECK constraint on nested keys, so this enum
 * is enforced at the API layer (ProfileController), not the database.
 *
 * V002's seed data used the lowercase string "requires_sponsorship" before
 * this enum existed; REQUIRES_SPONSORSHIP is its formalized equivalent.
 * Not retrofitting the seed migration (V002 is applied/immutable) — the
 * jsonb value stays as seeded, and the frontend/API layer should treat
 * unrecognized-but-present legacy values as informational rather than
 * rejecting them outright until re-saved through this validation.
 */
public enum VisaStatus {
    NOT_REQUIRED,
    REQUIRES_SPONSORSHIP,
    VISA_HELD,
    SETTLED_OR_CITIZEN
}
