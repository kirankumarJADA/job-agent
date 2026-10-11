package com.personal.jobagent.resume;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.documents.DocumentFactValidator;
import com.personal.jobagent.documents.PdfDocumentRenderer;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.profile.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a job-specific CV from the candidate's structured master profile.
 *
 * <p>Source of truth: the profile records (skills, work experience, education,
 * projects, certifications, profile fields and the account's name/email). The
 * CV text is assembled deterministically from those records — no LLM writes
 * CV content — so every line has a source record. The job description is
 * untrusted employer text and is used only to choose which verified skills to
 * emphasise and to report requirements the profile has no evidence for.
 *
 * <p>Every generation is a new immutable cv_versions row. Identical inputs
 * (same job text, same profile snapshot, same renderer and validator) replay
 * the existing version instead of creating another one.
 */
@Service
public class ResumeAtsIntelligenceService {
    /** Part of the input hash: a layout/validation change produces a new version. */
    static final String GENERATOR_VERSION = "cv-generator-2";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

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
    private final DocumentFactValidator validator = new DocumentFactValidator();

    public ResumeAtsIntelligenceService(JobRepository jobs, ProfileRepository profiles, ResumeAtsRepository repo,
                                        NotificationService notifications, CvArtifactService artifacts) {
        this.jobs = jobs;
        this.profiles = profiles;
        this.repo = repo;
        this.notifications = notifications;
        this.artifacts = artifacts;
    }

    /** Everything a tailored CV may draw on, loaded for one owner. */
    public record Source(ProfileRecord profile, ContactRecord contact, List<SkillRecord> skills,
                         List<WorkExperienceRecord> experiences, List<EducationRecord> education,
                         List<ProjectRecord> projects, List<CertificationRecord> certifications) {
        public DocumentFactValidator.CandidateFacts facts() {
            return new DocumentFactValidator.CandidateFacts(skills, experiences, education, projects, certifications,
                    profile.workEligibility(), profile.professionalSummary());
        }
    }

    public Source loadSource(UUID profileId) {
        ProfileRecord profile = profiles.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + profileId));
        return new Source(profile, profiles.findContact(profileId).orElse(new ContactRecord(null, null)),
                nonNull(profiles.findSkills(profileId)), nonNull(profiles.findExperiences(profileId)),
                nonNull(profiles.findEducation(profileId)), nonNull(profiles.findProjects(profileId)),
                nonNull(profiles.findCertifications(profileId)));
    }

    /** Hash of the exact profile content a CV is generated from (stored on every version). */
    public String snapshotHash(Source s) {
        return sha256(s.profile().masterRevision() + "|" + s.profile() + "|" + s.contact() + "|" + s.skills() + "|"
                + s.experiences() + "|" + s.education() + "|" + s.projects() + "|" + s.certifications());
    }

    public ResumeAtsAnalysis tailor(UUID profileId, UUID jobId, UUID applicationId) {
        JobRecord job = jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        Source source = loadSource(profileId);
        ProfileRecord profile = source.profile();
        List<SkillRecord> skills = source.skills();
        List<WorkExperienceRecord> exp = source.experiences();

        String profileSnapshotHash = snapshotHash(source);
        String jobSource = job.title() + "\n" + job.descriptionText() + "\n" + String.valueOf(job.skillsExtracted());
        String hash = sha256(jobSource + "|" + profileSnapshotHash + "|" + GENERATOR_VERSION + "|"
                + PdfDocumentRenderer.RENDERER_VERSION + "|" + DocumentFactValidator.VERSION);
        Optional<ResumeAtsAnalysis> existing = repo.find(profileId, jobId, hash, applicationId);
        if (existing.isPresent()) return repo.ensureArtifact(existing.get());

        List<String> required = extractSkills(job.descriptionText(), job.skillsExtracted());
        List<String> preferred = extractPreferred(job.descriptionText(), required);
        Map<String, String> normalized = new LinkedHashMap<>();
        required.forEach(s -> normalized.put(s, normalize(s)));
        Set<String> verifiedSkills = skills.stream().map(s -> normalize(s.name())).collect(Collectors.toSet());
        List<Map<String, Object>> evidence = new ArrayList<>();

        // Requirement evidence: a requirement is evidenced by a verified skill
        // record, or failing that by the text of a work-experience record.
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
                    String text = experience.title() + " " + String.join(" ", DocumentFactValidator.bullets(experience.bullets()));
                    if (contains(text, canonical)) {
                        evidence.add(claim("WORK_EXPERIENCE", experience.id(), canonical));
                        break;
                    }
                }
            }
        }
        // Every section rendered into the immutable CV receives a provenance link.
        if (profile.professionalSummary() != null && !profile.professionalSummary().isBlank()) {
            evidence.add(claim("PROFILE", profile.id(), "professional summary"));
        }
        for (SkillRecord skill : skills) evidence.add(claim("SKILL", skill.id(), skill.name()));
        for (WorkExperienceRecord experience : exp) evidence.add(claim("WORK_EXPERIENCE", experience.id(), experience.title() + " at " + experience.company()));
        for (EducationRecord education : source.education()) evidence.add(claim("EDUCATION", education.id(), education.qualification() + " at " + education.institution()));
        for (ProjectRecord project : source.projects()) evidence.add(claim("PROJECT", project.id(), project.name()));
        for (CertificationRecord cert : source.certifications()) evidence.add(claim("CERTIFICATION", cert.id(), cert.name()));
        evidence = evidence.stream().distinct().toList();

        Set<String> requiredSet = required.stream().map(this::normalize).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> evidencedRequirements = new LinkedHashSet<>();
        for (Map<String, Object> item : evidence) {
            String claimText = normalize(String.valueOf(item.get("claim")));
            if (requiredSet.contains(claimText) && Set.of("SKILL", "WORK_EXPERIENCE").contains(item.get("source_type"))) {
                evidencedRequirements.add(claimText);
            }
        }
        List<String> gaps = requiredSet.stream().filter(s -> !evidencedRequirements.contains(s)).toList();
        int keyword = required.isEmpty() ? 100 : (int) Math.round(100.0 * (requiredSet.size() - gaps.size()) / requiredSet.size());

        String markdown = render(source, requiredSet);
        DocumentFactValidator.Report validation = validator.validate(markdown, source.facts(),
                new DocumentFactValidator.JobContext(job.companyNameRaw(), required));
        PdfDocumentRenderer.Rendered pdf = artifacts.renderWithMetadata(markdown,
                "Tailored CV for " + job.title() + " (input " + hash.substring(0, 12) + ")");
        String contentSha256 = sha256(pdf.bytes());

        Map<String, Object> ats = new LinkedHashMap<>();
        ats.put("keyword_coverage", keyword);
        ats.put("requirement_coverage", keyword);
        ats.put("evidence_coverage", keyword);
        ats.put("evidenced_requirements", new ArrayList<>(evidencedRequirements));
        ats.put("format", Map.of("single_column", true, "canonical_sections", true, "tables", false, "graphics", false));
        ats.put("master_profile_revision", profile.masterRevision());
        ats.put("validation", validation.toMap());
        ats.put("renderer", Map.of("version", PdfDocumentRenderer.RENDERER_VERSION, "generator", GENERATOR_VERSION,
                "pages", pdf.pages(), "substituted_characters", pdf.substitutedCharacters()));
        ats.put("disclaimer", "Keyword coverage is a heuristic count of job keywords with evidence in your profile. "
                + "It is not an ATS score and does not guarantee ATS acceptance or an interview.");

        ResumeAtsAnalysis created = repo.insert(new ResumeAtsAnalysis(UuidV7.generate(), profileId, jobId, applicationId,
                hash, job.title(), domain(job.title() + "\n" + job.descriptionText()), required, preferred, normalized,
                evidence, gaps, ats, null, markdown, profile.masterRevision(), profileSnapshotHash, contentSha256),
                "Tailored CV - " + job.title(), false, pdf.bytes());
        try {
            notifications.emit(new NotificationService.NotificationCommand(NotificationEvents.CV_GENERATED, "CV", created.cvVersionId(),
                    Map.of("job_id", jobId.toString(), "application_id", applicationId == null ? "" : applicationId.toString(),
                            "profile_id", profileId.toString(),
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

    public List<String> extractSkills(String text, List<String> declared) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (declared != null) declared.stream().filter(Objects::nonNull).map(this::normalize).forEach(out::add);
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        ONTOLOGY.keySet().stream().filter(k -> containsWord(lower, k)).map(ONTOLOGY::get).forEach(out::add);
        return new ArrayList<>(out);
    }

    private List<String> extractPreferred(String text, List<String> required) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int p = Math.max(lower.indexOf("preferred"), lower.indexOf("nice to have"));
        if (p < 0) return List.of();
        return extractSkills(lower.substring(p), List.of()).stream().filter(s -> !required.contains(s)).toList();
    }

    public String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT).trim();
        return ONTOLOGY.getOrDefault(lower, lower.replaceAll("[^a-z0-9+#.-]", " ").replaceAll("\\s+", " ").trim());
    }

    private boolean contains(String text, String skill) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return (" " + lower + " ").contains(" " + skill.toLowerCase(Locale.ROOT) + " ") || lower.contains(skill.toLowerCase(Locale.ROOT));
    }

    /** Ontology keys such as "ml", "py" or "git" must match whole words, not substrings of other words. */
    private boolean containsWord(String lower, String key) {
        return java.util.regex.Pattern.compile("(?<![a-z0-9])" + java.util.regex.Pattern.quote(key) + "(?![a-z0-9])")
                .matcher(lower).find();
    }

    private String domain(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("data") || lower.contains("machine learning") || lower.contains("analytics")) return "DATA_ANALYTICS";
        if (lower.contains("software") || lower.contains("backend") || lower.contains("frontend")) return "SOFTWARE_ENGINEERING";
        return "UNCLASSIFIED";
    }

    /**
     * The CV text. Every line is a verified record or a field of one; nothing
     * is invented and nothing is copied from the job description. Job
     * relevance only changes ORDER (relevant verified skills first).
     */
    String render(Source s, Set<String> requiredNormalized) {
        ProfileRecord profile = s.profile();
        StringBuilder b = new StringBuilder("# ")
                .append(blankToNull(s.contact().displayName()) != null ? s.contact().displayName().strip() : "Curriculum Vitae")
                .append('\n');
        if (blankToNull(profile.headline()) != null) b.append(profile.headline().strip()).append('\n');
        List<String> contact = new ArrayList<>();
        if (blankToNull(s.contact().email()) != null) contact.add(s.contact().email().strip());
        if (blankToNull(profile.phone()) != null) contact.add(profile.phone().strip());
        if (blankToNull(profile.location()) != null) contact.add(profile.location().strip());
        if (!contact.isEmpty()) b.append(String.join(" · ", contact)).append('\n');
        List<String> links = new ArrayList<>();
        if (profile.links() != null) {
            profile.links().forEach((label, url) -> {
                if (url instanceof String text && !text.isBlank()) links.add(text.strip());
            });
        }
        if (!links.isEmpty()) b.append(String.join(" · ", links)).append('\n');

        if (blankToNull(profile.professionalSummary()) != null) {
            b.append("\n## Summary\n").append(profile.professionalSummary().strip()).append('\n');
        }

        if (!s.skills().isEmpty()) {
            List<String> relevant = s.skills().stream().filter(k -> requiredNormalized.contains(normalize(k.name())))
                    .map(SkillRecord::name).toList();
            List<String> other = s.skills().stream().filter(k -> !requiredNormalized.contains(normalize(k.name())))
                    .map(SkillRecord::name).toList();
            b.append("\n## Skills\n");
            if (!relevant.isEmpty()) b.append("**Relevant to this role:** ").append(String.join(", ", relevant)).append('\n');
            if (!other.isEmpty()) {
                b.append(relevant.isEmpty() ? "" : "**Other verified skills:** ").append(String.join(", ", other)).append('\n');
            }
        }

        if (!s.experiences().isEmpty()) {
            b.append("\n## Experience\n");
            for (WorkExperienceRecord x : s.experiences()) {
                b.append("### ").append(x.title()).append(" — ").append(x.company()).append('\n');
                List<String> meta = new ArrayList<>();
                if (x.startMonth() != null) meta.add(month(x.startMonth()) + " – " + (x.endMonth() == null ? "Present" : month(x.endMonth())));
                if (blankToNull(x.location()) != null) meta.add(x.location().strip());
                if (!meta.isEmpty()) b.append(String.join(" · ", meta)).append('\n');
                DocumentFactValidator.bullets(x.bullets()).forEach(t -> b.append("- ").append(t).append('\n'));
            }
        }

        if (!s.projects().isEmpty()) {
            b.append("\n## Projects\n");
            for (ProjectRecord x : s.projects()) {
                b.append("### ").append(x.name()).append('\n');
                if (blankToNull(x.url()) != null) b.append(x.url().strip()).append('\n');
                if (blankToNull(x.summary()) != null) b.append(x.summary().strip()).append('\n');
                DocumentFactValidator.bullets(x.bullets()).forEach(t -> b.append("- ").append(t).append('\n'));
            }
        }

        if (!s.education().isEmpty()) {
            b.append("\n## Education\n");
            for (EducationRecord x : s.education()) {
                b.append("### ").append(x.qualification()).append(" — ").append(x.institution()).append('\n');
                List<String> meta = new ArrayList<>();
                if (x.startYear() != null || x.endYear() != null) {
                    meta.add(x.startYear() != null && x.endYear() != null && !x.startYear().equals(x.endYear())
                            ? x.startYear() + " – " + x.endYear()
                            : String.valueOf(x.endYear() != null ? x.endYear() : x.startYear()));
                }
                if (blankToNull(x.field()) != null) meta.add(x.field().strip());
                if (blankToNull(x.grade()) != null) meta.add(x.grade().strip());
                if (!meta.isEmpty()) b.append(String.join(" · ", meta)).append('\n');
            }
        }

        if (!s.certifications().isEmpty()) {
            b.append("\n## Certifications\n");
            for (CertificationRecord c : s.certifications()) {
                b.append("- ").append(c.name());
                if (blankToNull(c.issuer()) != null) b.append(" — ").append(c.issuer().strip());
                if (c.issuedOn() != null) b.append(" (").append(month(c.issuedOn())).append(')');
                b.append('\n');
            }
        }
        return b.toString();
    }

    private static String month(LocalDate date) { return MONTH.format(date); }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private static <T> List<T> nonNull(List<T> list) { return list == null ? List.of() : list; }

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
