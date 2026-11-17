package com.personal.jobagent.resumeimport.dto;

import java.util.UUID;

public record CertificationCandidate(String clientId, ExtractedCertification data, MatchStatus matchStatus, UUID matchedExistingId) {
}
