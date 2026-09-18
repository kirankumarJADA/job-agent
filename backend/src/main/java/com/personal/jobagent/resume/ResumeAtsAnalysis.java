package com.personal.jobagent.resume;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ResumeAtsAnalysis(
        UUID id, UUID profileId, UUID jobId, UUID applicationId,
        String inputHash, String role, String domain,
        List<String> requiredSkills, List<String> preferredSkills,
        Map<String, String> normalizedSkills,
        List<Map<String, Object>> verifiedEvidence,
        List<String> gaps, Map<String, Object> atsReport,
        UUID cvVersionId, String resumeMarkdown,
        long profileRevision, String profileSnapshotHash, String contentSha256) {}
