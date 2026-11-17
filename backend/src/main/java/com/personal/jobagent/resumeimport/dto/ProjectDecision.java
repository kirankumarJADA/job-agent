package com.personal.jobagent.resumeimport.dto;

import java.util.List;
import java.util.UUID;

public record ProjectDecision(
        ImportAction action,
        UUID matchedExistingId,
        String name,
        String summary,
        String url,
        List<String> bullets
) {
}
