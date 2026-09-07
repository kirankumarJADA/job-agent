package com.personal.jobagent.profile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProjectRecord(
        UUID id, UUID profileId, String name, String summary, String url,
        List<Map<String, Object>> bullets, int sortOrder
) {
}
