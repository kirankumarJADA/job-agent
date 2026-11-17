package com.personal.jobagent.resumeimport;

import com.personal.jobagent.profile.*;
import com.personal.jobagent.resumeimport.dto.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Classifies each extracted item against the candidate's existing Master
 * Profile (section 8 of the spec): NEW / EXISTING / POSSIBLE_DUPLICATE /
 * CONFLICT / NEEDS_REVIEW. Never mutates anything — this is read-only
 * comparison, used by both the preview (informational) and confirm
 * (re-checked against current data before writing) stages.
 */
@Component
public class ProfileComparisonService {

    public record MatchResult(MatchStatus status, UUID matchedExistingId) {
        static final MatchResult NEW = new MatchResult(MatchStatus.NEW, null);
        static final MatchResult NEEDS_REVIEW = new MatchResult(MatchStatus.NEEDS_REVIEW, null);
    }

    public MatchResult compareExperience(ExtractedExperience candidate, List<WorkExperienceRecord> existing) {
        if (blank(candidate.company()) || blank(candidate.title())) return MatchResult.NEEDS_REVIEW;
        String company = normalize(candidate.company());
        String title = normalize(candidate.title());

        for (WorkExperienceRecord e : existing) {
            if (normalize(e.company()).equals(company) && normalize(e.title()).equals(title)) {
                boolean sameDates = Objects.equals(e.startMonth(), candidate.startMonth())
                        && Objects.equals(e.endMonth(), candidate.endMonth());
                return new MatchResult(sameDates ? MatchStatus.EXISTING : MatchStatus.CONFLICT, e.id());
            }
        }
        for (WorkExperienceRecord e : existing) {
            if (normalize(e.company()).equals(company)) {
                return new MatchResult(MatchStatus.POSSIBLE_DUPLICATE, e.id());
            }
        }
        return MatchResult.NEW;
    }

    public MatchResult compareEducation(ExtractedEducation candidate, List<EducationRecord> existing) {
        if (blank(candidate.institution()) || blank(candidate.qualification())) return MatchResult.NEEDS_REVIEW;
        String institution = normalize(candidate.institution());
        String qualification = normalize(candidate.qualification());

        for (EducationRecord e : existing) {
            if (normalize(e.institution()).equals(institution) && normalize(nullToEmpty(e.qualification())).equals(qualification)) {
                boolean sameDetail = Objects.equals(e.startYear(), candidate.startYear())
                        && Objects.equals(e.endYear(), candidate.endYear())
                        && normalize(nullToEmpty(e.field())).equals(normalize(nullToEmpty(candidate.field())));
                return new MatchResult(sameDetail ? MatchStatus.EXISTING : MatchStatus.CONFLICT, e.id());
            }
        }
        for (EducationRecord e : existing) {
            if (normalize(e.institution()).equals(institution)) {
                return new MatchResult(MatchStatus.POSSIBLE_DUPLICATE, e.id());
            }
        }
        return MatchResult.NEW;
    }

    public MatchResult compareProject(ExtractedProject candidate, List<ProjectRecord> existing) {
        if (blank(candidate.name())) return MatchResult.NEEDS_REVIEW;
        String name = normalize(candidate.name());

        for (ProjectRecord p : existing) {
            if (normalize(p.name()).equals(name)) {
                boolean sameUrl = normalize(nullToEmpty(p.url())).equals(normalize(nullToEmpty(candidate.url())));
                return new MatchResult(sameUrl ? MatchStatus.EXISTING : MatchStatus.CONFLICT, p.id());
            }
        }
        return MatchResult.NEW;
    }

    public MatchResult compareSkill(ExtractedSkill candidate, List<SkillRecord> existing) {
        if (blank(candidate.name())) return MatchResult.NEEDS_REVIEW;
        String name = normalizeSkill(candidate.name());
        for (SkillRecord s : existing) {
            if (normalizeSkill(s.name()).equals(name)) {
                return new MatchResult(MatchStatus.EXISTING, s.id());
            }
        }
        return MatchResult.NEW;
    }

    public MatchResult compareCertification(ExtractedCertification candidate, List<CertificationRecord> existing) {
        if (blank(candidate.name())) return MatchResult.NEEDS_REVIEW;
        String name = normalize(candidate.name());

        for (CertificationRecord c : existing) {
            if (normalize(c.name()).equals(name)) {
                boolean sameIssuer = normalize(nullToEmpty(c.issuer())).equals(normalize(nullToEmpty(candidate.issuer())));
                boolean sameCredential = normalize(nullToEmpty(c.credentialId())).equals(normalize(nullToEmpty(candidate.credentialId())));
                if (sameIssuer && sameCredential) return new MatchResult(MatchStatus.EXISTING, c.id());
                if (sameIssuer) return new MatchResult(MatchStatus.CONFLICT, c.id());
                return new MatchResult(MatchStatus.POSSIBLE_DUPLICATE, c.id());
            }
        }
        return MatchResult.NEW;
    }

    /** Employer, title, institution etc.: punctuation-insensitive ("Acme Corp." equals "Acme Corp"). */
    static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").strip();
    }

    /** Skills keep punctuation — "C#", "C++" and "C" are different skills — and compare case-insensitively like the database. */
    static String normalizeSkill(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
