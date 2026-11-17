package com.personal.jobagent.resumeimport.dto;

import java.util.UUID;

public record ProjectCandidate(String clientId, ExtractedProject data, MatchStatus matchStatus, UUID matchedExistingId) {
}
