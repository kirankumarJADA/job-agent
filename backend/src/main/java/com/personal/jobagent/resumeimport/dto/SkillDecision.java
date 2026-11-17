package com.personal.jobagent.resumeimport.dto;

import java.util.UUID;

public record SkillDecision(ImportAction action, UUID matchedExistingId, String name, String category) {
}
