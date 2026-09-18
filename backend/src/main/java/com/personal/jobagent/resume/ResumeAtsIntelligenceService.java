package com.personal.jobagent.resume;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.profile.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ResumeAtsIntelligenceService {
    private static final Map<String, String> ONTOLOGY = Map.ofEntries(
            Map.entry("python", "python"), Map.entry("py", "python"), Map.entry("sql", "sql"),
            Map.entry("postgres", "postgresql"), Map.entry("postgresql", "postgresql"), Map.entry("psql", "postgresql"),
            Map.entry("sklearn", "scikit-learn"), Map.entry("scikit-learn", "scikit-learn"),
            Map.entry("machine learning", "machine learning"), Map.entry("ml", "machine learning"),
            Map.entry("deep learning", "deep learning"), Map.entry("nlp", "natural language processing"),
            Map.entry("natural language processing", "natural language processing"), Map.entry("power bi", "power bi"),
            Map.entry("powerbi", "power bi"), Map.entry("java", "java"), Map.entry("javascript", "javascript"),
            Map.entry("typescript", "typescript"), Map.entry("react", "react"), Map.entry("spring boot", "spring boot"),
            Map.entry("docker", "docker"), Map.entry("kubernetes", "kubernetes"), Map.entry("aws", "aws"),
            Map.entry("azure", "azure"), Map.entry("git", "git"));

    private final JobRepository jobs;
    private final ProfileRepository profiles;
    private final ResumeAtsRepository repo;
    private final NotificationService notifications;
    private final CvArtifactService artifacts;

    public ResumeAtsIntelligenceService(JobRepository jobs, ProfileRepository profiles, ResumeAtsRepository repo,
                                        NotificationService notifications, CvArtifactService artifacts) {
        this.jobs = jobs;
        this.profiles = profiles;
        this.repo = repo;
        this.notifications = notifications;
        this.artifacts = artifacts;
    }

    public ResumeAtsAnalysis tailor(UUID profileId, UUID jobId, UUID applicationId) {
        JobRecord job = jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        ProfileRecord profile = profiles.findById(profileId).orElseThrow(() -> new IllegalArgumentException("Profile not found: " + profileId));
        List<SkillRecord> skills = profiles.findSkills(profileId);
        List<WorkExperienceRecord> exp = profiles.findExperiences(profileId);
        List<EducationRecord> edu = profiles.findEducation(profileId);
        List<ProjectRecord> projects = profiles.findProjects(profileId);

        String profileSnapshot = profile.masterRevision() + "|" + profile + "|" + skills + "|" + exp + "|" + edu + "|" + projects;
        String profileSnapshotHash = sha256(profileSnapshot);
        String jobSource = job.title() + "\n" + job.descriptionText() + "\n" + String.valueOf(job.skillsExtracted());
        String hash = sha256(jobSource + "|" + profileSnapshotHash);
        Optional<ResumeAtsAnalysis> existing = repo.find(profileId, jobId, hash, applicationId);
        if (existing.isPresent()) return repo.ensureArtifact(existing.get());

        List<String> required = extractSkills(job.descriptionText(), job.skillsExtracted());
        List<String> preferred = extractPreferred(job.descriptionText(), required);
        Map<String, String> normalized = new LinkedHashMap<>();
        required.forEach(s -> normalized.put(s, normalize(s)));
        Set<String> verifiedSkills = skills.stream().map(s -> normalize(s.name())).collect(Collectors.toSet());
        List<Map<String, Object>> evidence = new ArrayList<>();

        for (String requiredSkill : required) {
            String canonical = normalize(requiredSkill);
            for (SkillRecord skill : skills) {
                if (normalize(skill.name()).equals(canonical)) {
                    evidence.add(claim("SKILL", skill.id(), canonical));
                    break;
                }
            }
            if (!verifiedSkills.contains(canonical)) {
                for (WorkExperienceRecord experience : exp) {
                    if (contains(experience.title() + " " + experience.bullets(), canonical)) {
                        evidence.add(claim("WORK_EXPERIENCE", experience.id(), canonical));
                        break;
                    }
                }
            }
        }
        // Every section rendered into the immutable CV receives a provenance
        // link, not only JD-matched keywords.
        for (SkillRecord skill : skills) evidence.add(claim("SKILL", skill.id(), skill.name()));
        for (WorkExperienceRecord experience : exp) evidence.add(claim("WORK_EXPERIENCE", experience.id(), experience.title() + " at " + experience.company()));
        for (EducationRecord education : edu) evidence.add(claim("EDUCATION", education.id(), education.qualification() + " at " + education.institution()));
        for (ProjectRecord project : projects) evidence.add(claim("PROJECT", project.id(), project.name()));
        evidence = evidence.stream().distinct().toList();

        List<String> covered = evidence.stream().filter(x -> Set.of("SKILL", "WORK_EXPERIENCE").contains(x.get("source_type")))
                .map(x -> normalize(String.valueOf(x.get("claim")))).distinct().toList();
        List<String> gaps = required.stream().map(this::normalize).filter(s -> !covered.contains(s)).distinct().toList();
        int keyword = required.isEmpty() ? 100 : (int) Math.round(100.0 * (required.size() - gaps.size()) / required.size());
        String markdown = render(job, profile, skills, exp, edu, projects, covered);
        String contentSha256 = sha256(artifacts.renderPdf(markdown));
        Map<String, Object> ats = new LinkedHashMap<>();
        ats.put("keyword_coverage", keyword);
        ats.put("requirement_coverage", keyword);
        ats.put("evidence_coverage", required.isEmpty() ? 100 : (int) Math.round(100.0 * covered.size() / required.size()));
        ats.put("format", Map.of("single_column", true, "canonical_sections", true, "tables", false, "graphics", false));
        ats.put("master_profile_revision", profile.masterRevision());
        ats.put("disclaimer", "Heuristic indicator; no ATS score guarantees success.");

        ResumeAtsAnalysis created = repo.insert(new ResumeAtsAnalysis(UuidV7.generate(), profileId, jobId, applicationId,
                hash, job.title(), domain(job.title() + "\n" + job.descriptionText()), required, preferred, normalized,
                evidence, gaps, ats, null, markdown, profile.masterRevision(), profileSnapshotHash, contentSha256),
                "Tailored CV - " + job.title(), false);
        try {
            notifications.emit(new NotificationService.NotificationCommand(NotificationEvents.CV_GENERATED, "CV", created.cvVersionId(),
                    Map.of("job_id", jobId.toString(), "application_id", applicationId == null ? "" : applicationId.toString(),
                            "cv_version_id", created.cvVersionId().toString(), "input_hash", hash,
                            "profile_revision", profile.masterRevision()), UuidV7.generate(), null));
        } catch (Exception ignored) {
            // Durable artifact creation must not fail because notification delivery is unavailable.
        }
        return created;
    }

    private Map<String, Object> claim(String sourceType, UUID sourceId, String claim) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source_type", sourceType);
        value.put("evidence_id", sourceId.toString());
        value.put("claim", claim);
        value.put("evidence_status", "USER_VERIFIED");
        return value;
    }

    private List<String> extractSkills(String text, List<String> declared) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (declared != null) declared.stream().filter(Objects::nonNull).map(this::normalize).forEach(out::add);
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        ONTOLOGY.keySet().stream().filter(k -> contains(lower, k)).map(ONTOLOGY::get).forEach(out::add);
        return new ArrayList<>(out);
    }

    private List<String> extractPreferred(String text, List<String> required) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int p = Math.max(lower.indexOf("preferred"), lower.indexOf("nice to have"));
        if (p < 0) return List.of();
        return extractSkills(lower.substring(p), List.of()).stream().filter(s -> !required.contains(s)).toList();
    }

    private String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT).trim();
        return ONTOLOGY.getOrDefault(lower, lower.replaceAll("[^a-z0-9+#.-]", " ").replaceAll("\\s+", " "));
    }

    private boolean contains(String text, String skill) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return (" " + lower + " ").contains(" " + skill.toLowerCase(Locale.ROOT) + " ") || lower.contains(skill.toLowerCase(Locale.ROOT));
    }

    private String domain(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("data") || lower.contains("machine learning") || lower.contains("analytics")) return "DATA_ANALYTICS";
        if (lower.contains("software") || lower.contains("backend") || lower.contains("frontend")) return "SOFTWARE_ENGINEERING";
        return "UNCLASSIFIED";
    }

    private String render(JobRecord job, ProfileRecord profile, List<SkillRecord> skills,
                          List<WorkExperienceRecord> experiences, List<EducationRecord> education,
                          List<ProjectRecord> projects, List<String> covered) {
        StringBuilder b = new StringBuilder("# ").append(job.title()).append("\n\n## Summary\n")
                .append(profile.professionalSummary() == null ? "Truthful, job-specific profile aligned to verified evidence." : profile.professionalSummary())
                .append("\n\n## Skills\n").append(String.join(", ", covered)).append("\n\n## Experience\n");
        experiences.forEach(x -> b.append("### ").append(x.title()).append(" — ").append(x.company()).append("\n").append(x.bullets()).append("\n"));
        b.append("\n## Projects\n");
        projects.forEach(x -> b.append("### ").append(x.name()).append("\n").append(x.summary()).append("\n"));
        b.append("\n## Education\n");
        education.forEach(x -> b.append(x.qualification()).append(" — ").append(x.institution()).append("\n"));
        return b.toString();
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte item : hash) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
