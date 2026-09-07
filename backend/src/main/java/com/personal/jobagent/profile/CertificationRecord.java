package com.personal.jobagent.profile;

import java.time.LocalDate;
import java.util.UUID;

public record CertificationRecord(
        UUID id, UUID profileId, String name, String issuer, LocalDate issuedOn, String credentialId
) {
}
