package com.personal.jobagent.resumeimport.dto;

import java.util.UUID;

public record ExperienceCandidate(String clientId, ExtractedExperience data, MatchStatus matchStatus, UUID matchedExistingId) {
}
