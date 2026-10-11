package com.personal.jobagent.resume;

import com.personal.jobagent.documents.DocumentFactValidator;
import com.personal.jobagent.profile.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Deterministic comparison between the candidate's structured master profile
 * (the source of truth — Robin has no separately uploaded "master CV" file)
 * and one stored, immutable tailored CV.
 *
 * <p>Everything here is computed from stored data: the CV's markdown,
 * evidence and gaps as persisted at generation time, and the owner's current
 * profile records. No model is asked to describe the difference. When the
 * profile has changed since the CV was generated (snapshot hash differs), the
 * comparison says so: it then compares against the CURRENT profile, which is
 * not necessarily what the CV was generated from.
 */
@Service
public class CvComparisonService {

    private final ResumeAtsRepository repository;
    private final ResumeAtsIntelligenceService tailoring;

    public CvComparisonService(ResumeAtsRepository repository, ResumeAtsIntelligenceService tailoring) {
        this.repository = repository;
        this.tailoring = tailoring;
    }

    public Optional<Map<String, Object>> compare(UUID profileId, UUID cvVersionId) {
        Optional<ResumeAtsAnalysis> found = repository.findByCvVersion(profileId, cvVersionId);
        if (found.isEmpty()) return Optional.empty();
        ResumeAtsAnalysis cv = found.get();
        ResumeAtsIntelligenceService.Source source = tailoring.loadSource(profileId);
        String markdown = cv.resumeMarkdown() == null ? "" : cv.resumeMarkdown();
        String currentHash = tailoring.snapshotHash(source);
        boolean sameSource = currentHash.equals(cv.profileSnapshotHash());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cvVersionId", cvVersionId);
        result.put("jobId", cv.jobId());
        result.put("generatedFromProfileRevision", cv.profileRevision());
        result.put("currentProfileRevision", source.profile().masterRevision());
        result.put("sourceMatchesGeneration", sameSource);
        result.put("sourceNote", sameSource
                ? "Your profile is unchanged since this CV was generated, so this comparison is against the exact source it was built from."
                : "Your profile has changed since this CV was generated (revision " + cv.profileRevision() + " → "
                  + source.profile().masterRevision() + "). This comparison uses your current profile; regenerate to tailor from it.");
        result.put("method", "Deterministic text and record comparison. No AI is used to describe differences.");

        // ---- skills -------------------------------------------------------
        Set<String> required = new LinkedHashSet<>();
        cv.requiredSkills().forEach(r -> required.add(tailoring.normalize(r)));
        String skillsSection = section(markdown, "Skills");
        List<Map<String, Object>> emphasised = new ArrayList<>();
        List<Map<String, Object>> retained = new ArrayList<>();
        List<Map<String, Object>> omitted = new ArrayList<>();
        for (SkillRecord skill : source.skills()) {
            Map<String, Object> item = item(skill.id(), skill.name());
            boolean present = containsWord(skillsSection, skill.name());
            if (!present) omitted.add(item);
            else if (required.contains(tailoring.normalize(skill.name()))) emphasised.add(item);
            else retained.add(item);
        }
        result.put("skills", Map.of("emphasisedForJob", emphasised, "retained", retained, "omitted", omitted));

        // ---- requirements -------------------------------------------------
        List<Map<String, Object>> evidenced = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> e : cv.verifiedEvidence()) {
            String claim = tailoring.normalize(String.valueOf(e.get("claim")));
            if (required.contains(claim) && seen.add(claim)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("requirement", claim);
                row.put("sourceType", e.get("source_type"));
                row.put("sourceId", e.get("evidence_id"));
                row.put("sourceLabel", label(source, String.valueOf(e.get("source_type")), String.valueOf(e.get("evidence_id"))));
                evidenced.add(row);
            }
        }
        result.put("requirements", Map.of("evidenced", evidenced, "missingFromProfile", cv.gaps(),
                "preferred", cv.preferredSkills(),
                "note", "Missing requirements are job keywords with no matching skill or work-experience evidence in your profile. "
                        + "They are not added to the CV."));

        // ---- sections -----------------------------------------------------
        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(Map.of("section", "Experience", "items", source.experiences().stream().map(x ->
                entry(x.id(), x.title() + " — " + x.company(), markdown, "### " + x.title() + " — " + x.company(),
                        DocumentFactValidator.bullets(x.bullets()))).toList()));
        sections.add(Map.of("section", "Projects", "items", source.projects().stream().map(x ->
                entry(x.id(), x.name(), markdown, "### " + x.name(), DocumentFactValidator.bullets(x.bullets()))).toList()));
        sections.add(Map.of("section", "Education", "items", source.education().stream().map(x ->
                entry(x.id(), x.qualification() + " — " + x.institution(), markdown,
                        "### " + x.qualification() + " — " + x.institution(), List.of())).toList()));
        sections.add(Map.of("section", "Certifications", "items", source.certifications().stream().map(x ->
                entry(x.id(), x.name(), markdown, "- " + x.name(), List.of())).toList()));
        result.put("sections", sections);

        Set<String> knownHeadings = new HashSet<>();
        source.experiences().forEach(x -> knownHeadings.add(x.title() + " — " + x.company()));
        source.projects().forEach(x -> knownHeadings.add(x.name()));
        source.education().forEach(x -> knownHeadings.add(x.qualification() + " — " + x.institution()));
        List<String> notInProfile = markdown.lines().filter(l -> l.startsWith("### "))
                .map(l -> l.substring(4).strip()).filter(h -> !knownHeadings.contains(h)).toList();
        result.put("inCvButNotInCurrentProfile", notInProfile);

        // ---- attention ----------------------------------------------------
        List<Map<String, Object>> attention = new ArrayList<>();
        Object validation = cv.atsReport() == null ? null : cv.atsReport().get("validation");
        if (validation instanceof Map<?, ?> v && v.get("findings") instanceof List<?> findings) {
            for (Object f : findings) {
                if (f instanceof Map<?, ?> finding) {
                    attention.add(Map.of("severity", String.valueOf(finding.get("severity")),
                            "kind", String.valueOf(finding.get("kind")), "message", String.valueOf(finding.get("message"))));
                }
            }
        } else {
            attention.add(Map.of("severity", "WARNING", "kind", "DETERMINISTIC",
                    "message", "This CV was generated before Phase 8.1 checks and PDF rendering. Regenerate it to validate it and get a readable PDF."));
        }
        if (!sameSource) attention.add(Map.of("severity", "WARNING", "kind", "DETERMINISTIC", "message", result.get("sourceNote")));
        if (!notInProfile.isEmpty()) attention.add(Map.of("severity", "WARNING", "kind", "DETERMINISTIC",
                "message", "The CV contains entries no longer in your profile: " + String.join("; ", notInProfile)));
        if (!cv.gaps().isEmpty()) attention.add(Map.of("severity", "WARNING", "kind", "DETERMINISTIC",
                "message", cv.gaps().size() + " job requirement(s) have no evidence in your profile: " + String.join(", ", cv.gaps())));
        if (source.profile().professionalSummary() == null || source.profile().professionalSummary().isBlank()) {
            attention.add(Map.of("severity", "WARNING", "kind", "DETERMINISTIC",
                    "message", "Your profile has no professional summary, so the CV has no summary section."));
        }
        Object renderer = cv.atsReport() == null ? null : cv.atsReport().get("renderer");
        if (renderer instanceof Map<?, ?> r && r.get("substituted_characters") instanceof Number n && n.intValue() > 0) {
            attention.add(Map.of("severity", "WARNING", "kind", "DETERMINISTIC",
                    "message", n.intValue() + " character(s) could not be drawn by the PDF font and appear as \"?\"."));
        }
        result.put("attention", attention);

        Map<String, Object> sourceRecords = new LinkedHashMap<>();
        sourceRecords.put("summaryPresent", source.profile().professionalSummary() != null && !source.profile().professionalSummary().isBlank());
        sourceRecords.put("skills", source.skills().stream().map(s -> item(s.id(), s.name())).toList());
        sourceRecords.put("experiences", source.experiences().stream().map(x -> item(x.id(), x.title() + " — " + x.company())).toList());
        sourceRecords.put("projects", source.projects().stream().map(x -> item(x.id(), x.name())).toList());
        sourceRecords.put("education", source.education().stream().map(x -> item(x.id(), x.qualification() + " — " + x.institution())).toList());
        sourceRecords.put("certifications", source.certifications().stream().map(x -> item(x.id(), x.name())).toList());
        result.put("sourceRecords", sourceRecords);
        return Optional.of(result);
    }

    private Map<String, Object> entry(UUID id, String label, String markdown, String marker, List<String> sourceBullets) {
        Map<String, Object> row = new LinkedHashMap<>(item(id, label));
        boolean present = markdown.lines().anyMatch(l -> l.strip().equals(marker));
        int rendered = (int) sourceBullets.stream().filter(b -> markdown.lines().anyMatch(l -> l.strip().equals("- " + b))).count();
        row.put("sourceBullets", sourceBullets.size());
        row.put("renderedBullets", rendered);
        row.put("status", !present ? "OMITTED" : rendered < sourceBullets.size() ? "SHORTENED" : "RETAINED");
        return row;
    }

    private static Map<String, Object> item(UUID id, String label) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("label", label);
        return map;
    }

    private String label(ResumeAtsIntelligenceService.Source s, String type, String id) {
        return switch (type) {
            case "SKILL" -> s.skills().stream().filter(x -> x.id().toString().equals(id)).map(x -> "Skill: " + x.name()).findFirst().orElse("Skill record (no longer in profile)");
            case "WORK_EXPERIENCE" -> s.experiences().stream().filter(x -> x.id().toString().equals(id))
                    .map(x -> "Experience: " + x.title() + " at " + x.company()).findFirst().orElse("Experience record (no longer in profile)");
            default -> type;
        };
    }

    private static String section(String markdown, String heading) {
        StringBuilder out = new StringBuilder();
        boolean in = false;
        for (String line : markdown.split("\\R")) {
            if (line.startsWith("## ")) in = line.substring(3).strip().equalsIgnoreCase(heading);
            else if (in) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static boolean containsWord(String text, String word) {
        return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(word) + "(?![\\p{L}\\p{N}])",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find();
    }
}
