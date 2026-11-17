package com.personal.jobagent.resumeimport.dto;

import java.util.UUID;

public record EducationCandidate(String clientId, ExtractedEducation data, MatchStatus matchStatus, UUID matchedExistingId) {
}
