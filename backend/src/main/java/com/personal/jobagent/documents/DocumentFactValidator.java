package com.personal.jobagent.documents;

import com.personal.jobagent.profile.CertificationRecord;
import com.personal.jobagent.profile.EducationRecord;
import com.personal.jobagent.profile.ProjectRecord;
import com.personal.jobagent.profile.SkillRecord;
import com.personal.jobagent.profile.WorkExperienceRecord;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks generated application text (cover letters, drafted answers, tailored
 * CVs) against the candidate's own profile records.
 *
 * <p>Two kinds of finding are kept apart on purpose:
 * <ul>
 *   <li><b>DETERMINISTIC</b> — the text states a checkable fact (a year, a
 *       number of years, a figure, a degree, a named certification, a work
 *       authorisation or clearance) that no profile record supports. These are
 *       BLOCKERs: the document cannot be approved until the text is corrected
 *       or the profile is.</li>
 *   <li><b>HEURISTIC</b> — pattern-based suspicions (an employer name the
 *       candidate never worked at, a job requirement mentioned without
 *       evidence). These are WARNINGs for a human to read; they may be false
 *       positives and never claim certainty.</li>
 * </ul>
 * Passing this check means "no unsupported claim of these kinds was found",
 * not "every sentence is true". Employer text is never consulted for
 * candidate facts; job requirements are only used to warn when they appear in
 * the text without candidate evidence.
 */
public final class DocumentFactValidator {

    public static final String VERSION = "fact-validator-1";

    public enum Severity { BLOCKER, WARNING }
    public enum Kind { DETERMINISTIC, HEURISTIC }

    public record Finding(String code, Severity severity, Kind kind, String claim, String message) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", code);
            map.put("severity", severity.name());
            map.put("kind", kind.name());
            map.put("claim", claim);
            map.put("message", message);
            return map;
        }
    }

    public record Report(List<Finding> findings) {
        public boolean passed() { return findings.stream().noneMatch(f -> f.severity() == Severity.BLOCKER); }
        public long blockers() { return findings.stream().filter(f -> f.severity() == Severity.BLOCKER).count(); }
        public long warnings() { return findings.stream().filter(f -> f.severity() == Severity.WARNING).count(); }
        public List<String> messages() { return findings.stream().map(Finding::message).toList(); }
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("validator_version", VERSION);
            map.put("passed", passed());
            map.put("blocker_count", blockers());
            map.put("warning_count", warnings());
            map.put("findings", findings.stream().map(Finding::toMap).toList());
            map.put("scope", "Checks dates, years of experience, figures, degrees, named certifications, "
                    + "work authorisation and clearance against your profile records, plus heuristic warnings. "
                    + "Passing does not prove every sentence is true.");
            return map;
        }
    }

    /** The candidate's own records. Employer content never goes in here. */
    public record CandidateFacts(List<SkillRecord> skills, List<WorkExperienceRecord> experiences,
                                 List<EducationRecord> education, List<ProjectRecord> projects,
                                 List<CertificationRecord> certifications, Map<String, Object> workEligibility,
                                 String professionalSummary) {
        public CandidateFacts {
            skills = skills == null ? List.of() : skills;
            experiences = experiences == null ? List.of() : experiences;
            education = education == null ? List.of() : education;
            projects = projects == null ? List.of() : projects;
            certifications = certifications == null ? List.of() : certifications;
            workEligibility = workEligibility == null ? Map.of() : workEligibility;
        }
    }

    /** What the employer's posting asks for — untrusted, only used for warnings. */
    public record JobContext(String hiringCompany, List<String> requirements) {
        public JobContext {
            requirements = requirements == null ? List.of() : requirements;
        }
    }

    private static final Pattern YEAR = Pattern.compile("\\b(19[5-9]\\d|20\\d{2})\\b");
    private static final Pattern YEARS_OF_EXPERIENCE = Pattern.compile(
            "\\b(\\d{1,2})\\s*\\+?\\s*(?:years|yrs)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIGURE = Pattern.compile(
            "(?:[£$€]\\s?\\d[\\d,.]*\\s?[kKmMbB]?\\b)|(?:\\b\\d[\\d,.]*\\s?%)|(?:\\b\\d[\\d,.]*\\s?[xX]\\b)|(?:\\b\\d+(?:\\.\\d+)?[kKmM]\\b)");
    private static final Pattern EMPLOYER = Pattern.compile(
            "\\b(?:(?:worked|working|employed)\\s+(?:at|with|for)|(?:internship|experience)\\s+at)\\s+"
                    + "((?:[A-Z][\\w&.'\\-]*)(?:\\s+(?:[A-Z][\\w&.'\\-]*|&|of|and))*)");

    private record DegreeFamily(String label, List<String> textTerms, List<String> recordTerms) {}

    private static final List<DegreeFamily> DEGREES = List.of(
            new DegreeFamily("doctorate", List.of("phd", "ph.d", "doctorate", "doctoral"), List.of("phd", "ph.d", "doctor", "dphil")),
            new DegreeFamily("MBA", List.of("mba"), List.of("mba", "business administration")),
            new DegreeFamily("master's degree", List.of("msc", "m.sc", "master's degree", "masters degree", "master of"),
                    List.of("msc", "m.sc", "master", "ma ", "meng", "mres", "mphil", "llm", "mba")),
            new DegreeFamily("bachelor's degree", List.of("bsc", "b.sc", "bachelor", "undergraduate degree"),
                    List.of("bsc", "b.sc", "bachelor", "ba ", "beng", "llb", "ba(hons)", "bsc(hons)")));

    private static final List<String> CERT_ACRONYMS = List.of(
            "aws certified", "azure certified", "google cloud certified", "pmp", "cissp", "cism", "cisa", "ceh",
            "comptia", "ccna", "ccnp", "acca", "cima", "cfa", "cpa", "prince2", "itil", "certified scrum",
            "csm", "safe agilist", "ckad", "cka", "oracle certified", "tableau certified", "microsoft certified");

    private static final List<String> WORK_AUTH_TERMS = List.of(
            "right to work", "work permit", "visa", "visa sponsorship", "require sponsorship", "requires sponsorship",
            "need sponsorship", "needs sponsorship", "citizen", "citizenship",
            "settled status", "pre-settled", "indefinite leave", "authorised to work", "authorized to work",
            "eligible to work", "green card", "work authorisation", "work authorization", "permanent resident");
    private static final Pattern SPONSORSHIP_NOT_REQUIRED = Pattern.compile(
            "\\b(?:do not|don't|dont|does not|doesn't|never)\\s+(?:currently\\s+)?(?:need|require)\\s+(?:a\\s+)?(?:visa\\s+)?sponsorship\\b"
                    + "|\\bno\\s+(?:visa\\s+)?sponsorship\\s+(?:is\\s+)?required\\b"
                    + "|\\bwithout\\s+(?:a\\s+)?(?:visa\\s+)?sponsorship\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPONSORSHIP_REQUIRED = Pattern.compile(
            "\\b(?:need|needs|require|requires|requiring)\\s+(?:a\\s+)?(?:visa\\s+)?sponsorship\\b",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> CLEARANCE_TERMS = List.of(
            "security clearance", "sc cleared", "dv cleared", "sc clearance", "dv clearance", "top secret", "nato secret");

    public Report validate(String text, CandidateFacts facts, JobContext job) {
        List<Finding> findings = new ArrayList<>();
        if (text == null || text.isBlank()) return new Report(findings);
        String lower = text.toLowerCase(Locale.ROOT);
        String source = sourceText(facts).toLowerCase(Locale.ROOT);

        checkYears(text, facts, findings);
        checkYearsOfExperience(text, facts, findings);
        checkFigures(text, source, findings);
        checkDegrees(lower, facts, findings);
        checkCertifications(lower, facts, findings);
        checkWorkAuthorisation(lower, facts, findings);
        checkClearance(lower, source, findings);
        checkEmployers(text, facts, job, findings);
        checkRequirementsWithoutEvidence(lower, facts, job, source, findings);
        return new Report(findings.stream().distinct().toList());
    }

    private void checkYears(String text, CandidateFacts facts, List<Finding> findings) {
        Set<Integer> covered = new HashSet<>();
        int now = LocalDate.now().getYear();
        covered.add(now);
        for (WorkExperienceRecord e : facts.experiences()) {
            if (e.startMonth() == null) continue;
            int end = e.endMonth() == null ? now : e.endMonth().getYear();
            for (int y = e.startMonth().getYear(); y <= end; y++) covered.add(y);
        }
        for (EducationRecord ed : facts.education()) {
            Integer start = ed.startYear() != null ? ed.startYear() : ed.endYear();
            Integer end = ed.endYear() != null ? ed.endYear() : ed.startYear();
            if (start == null) continue;
            for (int y = start; y <= end; y++) covered.add(y);
        }
        for (CertificationRecord c : facts.certifications()) {
            if (c.issuedOn() != null) covered.add(c.issuedOn().getYear());
        }
        Matcher m = YEAR.matcher(text);
        Set<Integer> reported = new HashSet<>();
        while (m.find()) {
            int year = Integer.parseInt(m.group(1));
            if (!covered.contains(year) && reported.add(year)) {
                findings.add(new Finding("UNSUPPORTED_DATE", Severity.BLOCKER, Kind.DETERMINISTIC, String.valueOf(year),
                        "The text cites " + year + ", which no dated work, education or certification record in your profile covers."));
            }
        }
    }

    private void checkYearsOfExperience(String text, CandidateFacts facts, List<Finding> findings) {
        // Count completed months across the recorded work periods. The previous
        // earliest-start-year-to-now calculation ignored end dates and career
        // gaps, allowing short/old roles to support inflated experience claims.
        int workYears = totalDocumentedExperienceMonths(facts.experiences()) / 12;
        int skillYears = facts.skills().stream().filter(s -> s.years() != null)
                .mapToInt(s -> Math.max(0, s.years().intValue())).max().orElse(0);
        int supported = Math.max(workYears, skillYears);
        Matcher m = YEARS_OF_EXPERIENCE.matcher(text);
        while (m.find()) {
            int claimed = Integer.parseInt(m.group(1));
            if (claimed > supported) {
                findings.add(new Finding("UNSUPPORTED_YEARS_OF_EXPERIENCE", Severity.BLOCKER, Kind.DETERMINISTIC, m.group(),
                        "The text claims " + m.group().trim() + ", but your non-overlapping dated work periods and skill records support at most "
                                + supported + " completed year(s)."));
            }
        }
    }

    private int totalDocumentedExperienceMonths(List<WorkExperienceRecord> experiences) {
        record MonthRange(YearMonth start, YearMonth end) {}
        YearMonth current = YearMonth.now();
        List<MonthRange> ranges = new ArrayList<>();
        for (WorkExperienceRecord experience : experiences) {
            if (experience.startMonth() == null) continue;
            YearMonth start = YearMonth.from(experience.startMonth());
            if (start.isAfter(current)) continue;
            YearMonth end = experience.endMonth() == null ? current : YearMonth.from(experience.endMonth());
            if (end.isAfter(current)) end = current;
            if (end.isBefore(start)) continue;
            ranges.add(new MonthRange(start, end));
        }
        if (ranges.isEmpty()) return 0;
        ranges.sort(Comparator.comparing(MonthRange::start));

        long total = 0;
        YearMonth rangeStart = ranges.getFirst().start();
        YearMonth rangeEnd = ranges.getFirst().end();
        for (int i = 1; i < ranges.size(); i++) {
            MonthRange next = ranges.get(i);
            if (!next.start().isAfter(rangeEnd.plusMonths(1))) {
                if (next.end().isAfter(rangeEnd)) rangeEnd = next.end();
            } else {
                total += ChronoUnit.MONTHS.between(rangeStart, rangeEnd) + 1;
                rangeStart = next.start();
                rangeEnd = next.end();
            }
        }
        total += ChronoUnit.MONTHS.between(rangeStart, rangeEnd) + 1;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private void checkFigures(String text, String source, List<Finding> findings) {
        Matcher m = FIGURE.matcher(text);
        while (m.find()) {
            String figure = m.group().trim();
            // Preserve units and currency: a source "30%" is not evidence for
            // a generated claim of "£30" merely because both contain the digits 30.
            if (!source.contains(figure.toLowerCase(Locale.ROOT))) {
                findings.add(new Finding("UNSUPPORTED_FIGURE", Severity.BLOCKER, Kind.DETERMINISTIC, figure,
                        "The figure \"" + figure + "\" does not appear in any of your profile records. Quantitative "
                                + "achievements must come from your own records."));
            }
        }
    }

    private void checkDegrees(String lower, CandidateFacts facts, List<Finding> findings) {
        String quals = facts.education().stream()
                .map(e -> (" " + e.qualification() + " " + Objects.toString(e.field(), "") + " ").toLowerCase(Locale.ROOT))
                .reduce("", String::concat);
        for (DegreeFamily degree : DEGREES) {
            Optional<String> mentioned = degree.textTerms().stream().filter(t -> containsTerm(lower, t)).findFirst();
            if (mentioned.isEmpty()) continue;
            boolean held = degree.recordTerms().stream().anyMatch(t -> containsTerm(quals, t.trim()));
            if (!held) {
                findings.add(new Finding("UNSUPPORTED_QUALIFICATION", Severity.BLOCKER, Kind.DETERMINISTIC, mentioned.get(),
                        "The text mentions a " + degree.label() + " (\"" + mentioned.get() + "\"), but no education record in your profile holds one."));
            }
        }
    }

    private void checkCertifications(String lower, CandidateFacts facts, List<Finding> findings) {
        String certs = facts.certifications().stream()
                .map(c -> (c.name() + " " + Objects.toString(c.issuer(), "")).toLowerCase(Locale.ROOT))
                .reduce("", (a, b) -> a + " | " + b);
        for (String term : CERT_ACRONYMS) {
            if (containsTerm(lower, term) && !certs.contains(term)) {
                findings.add(new Finding("UNSUPPORTED_CERTIFICATION", Severity.BLOCKER, Kind.DETERMINISTIC, term,
                        "The text mentions \"" + term + "\", which is not among your recorded certifications."));
            }
        }
        if ((lower.contains("certified") || lower.contains("certification")) && facts.certifications().isEmpty()) {
            findings.add(new Finding("CERTIFICATION_MENTION", Severity.WARNING, Kind.HEURISTIC, "certification",
                    "The text mentions a certification and your profile records none. Check it does not claim one."));
        }
    }

    private void checkWorkAuthorisation(String lower, CandidateFacts facts, List<Finding> findings) {
        Optional<String> term = WORK_AUTH_TERMS.stream().filter(t -> containsTerm(lower, t)).findFirst();
        if (term.isEmpty()) return;

        Map<String, Object> eligibility = facts.workEligibility();
        Boolean recordedRightToWork = booleanValue(firstPresent(eligibility, "right_to_work_uk", "rightToWorkUk"));
        String visaStatus = normaliseVisaStatus(firstPresent(eligibility, "visa_status", "visaStatus"));
        boolean contradiction = false;
        boolean specificallyChecked = false;

        boolean mentionsRightToWork = List.of("right to work", "work permit", "authorised to work",
                "authorized to work", "eligible to work").stream().anyMatch(t -> containsTerm(lower, t));
        if (mentionsRightToWork && recordedRightToWork != null) {
            Boolean claimedRightToWork = rightToWorkClaim(lower);
            if (claimedRightToWork != null) {
                specificallyChecked = true;
                if (!recordedRightToWork.equals(claimedRightToWork)) {
                    findings.add(new Finding("CONTRADICTED_WORK_AUTHORISATION", Severity.BLOCKER, Kind.DETERMINISTIC,
                            term.get(), "The text's right-to-work claim contradicts the right_to_work_uk value recorded in your profile."));
                    contradiction = true;
                }
            }
        }

        Boolean claimedSponsorship = sponsorshipRequirementClaim(lower);
        if (claimedSponsorship != null) {
            Boolean recordedSponsorship = sponsorshipRequirement(visaStatus);
            if (recordedSponsorship != null) {
                specificallyChecked = true;
                if (!recordedSponsorship.equals(claimedSponsorship)) {
                    findings.add(new Finding("CONTRADICTED_SPONSORSHIP_REQUIREMENT", Severity.BLOCKER, Kind.DETERMINISTIC,
                            term.get(), "The text's sponsorship claim contradicts the visa status recorded in your profile."));
                    contradiction = true;
                }
            }
        }

        if (contradiction) return;
        if (specificallyChecked) return;
        if (eligibility.isEmpty()) {
            findings.add(new Finding("UNSUPPORTED_WORK_AUTHORISATION", Severity.BLOCKER, Kind.DETERMINISTIC, term.get(),
                    "The text refers to work authorisation (\"" + term.get() + "\") but your profile records no work-eligibility details."));
        } else {
            findings.add(new Finding("WORK_AUTHORISATION_STATEMENT", Severity.WARNING, Kind.HEURISTIC, term.get(),
                    "The text makes a work-authorisation statement that the structured profile cannot fully verify. Confirm the country and exact permission before approval."));
        }
    }

    private static Object firstPresent(Map<String, Object> values, String first, String second) {
        Object value = values.get(first);
        return value != null ? value : values.get(second);
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s.trim())) return Boolean.FALSE;
        }
        return null;
    }

    private static String normaliseVisaStatus(Object value) {
        return value == null ? "" : String.valueOf(value).trim().replaceAll("[\\s-]+", "_").toUpperCase(Locale.ROOT);
    }

    private static Boolean sponsorshipRequirement(String visaStatus) {
        return switch (visaStatus) {
            case "REQUIRES_SPONSORSHIP" -> Boolean.TRUE;
            case "NOT_REQUIRED", "SETTLED_OR_CITIZEN" -> Boolean.FALSE;
            default -> null; // VISA_HELD and legacy/unknown values do not prove sponsorship needs.
        };
    }

    private static Boolean sponsorshipRequirementClaim(String lower) {
        if (SPONSORSHIP_NOT_REQUIRED.matcher(lower).find()) return Boolean.FALSE;
        if (SPONSORSHIP_REQUIRED.matcher(lower).find()) return Boolean.TRUE;
        return null;
    }

    private static Boolean rightToWorkClaim(String lower) {
        boolean negative = Pattern.compile(
                "\\b(?:do not|don't|dont|does not|doesn't|cannot|can't|not|never)\\s+(?:(?:currently|legally)\\s+)?(?:(?:have|hold)\\s+)?(?:the\\s+)?right to work\\b",
                Pattern.CASE_INSENSITIVE).matcher(lower).find()
                || Pattern.compile("\\bno right to work\\b", Pattern.CASE_INSENSITIVE).matcher(lower).find()
                || Pattern.compile("\\bnot\\s+(?:legally\\s+)?(?:authorised|authorized|eligible)\\s+to\\s+work\\b",
                Pattern.CASE_INSENSITIVE).matcher(lower).find();
        return negative ? Boolean.FALSE : Boolean.TRUE;
    }

    private void checkClearance(String lower, String source, List<Finding> findings) {
        for (String term : CLEARANCE_TERMS) {
            if (containsTerm(lower, term) && !source.contains(term)) {
                findings.add(new Finding("UNSUPPORTED_CLEARANCE", Severity.BLOCKER, Kind.DETERMINISTIC, term,
                        "The text mentions \"" + term + "\", which none of your profile records state."));
            }
        }
    }

    private void checkEmployers(String text, CandidateFacts facts, JobContext job, List<Finding> findings) {
        // Only recorded employers can substantiate an employment claim. The
        // target hiring company, a school, project, or skill is not proof that
        // the candidate previously worked there.
        List<String> known = new ArrayList<>();
        facts.experiences().forEach(e -> known.add(e.company().toLowerCase(Locale.ROOT)));
        Matcher m = EMPLOYER.matcher(text);
        while (m.find()) {
            String org = m.group(1).trim();
            String orgLower = org.toLowerCase(Locale.ROOT);
            boolean recognised = known.stream().anyMatch(k -> !k.isBlank() && (k.contains(orgLower) || orgLower.contains(k)));
            if (!recognised) {
                findings.add(new Finding("UNRECOGNISED_ORGANISATION", Severity.WARNING, Kind.HEURISTIC, org,
                        "The text appears to place you at \"" + org + "\", which is not an employer, institution or project in your profile."));
            }
        }
    }

    private void checkRequirementsWithoutEvidence(String lower, CandidateFacts facts, JobContext job, String source,
                                                  List<Finding> findings) {
        if (job == null) return;
        Set<String> skills = new HashSet<>();
        facts.skills().forEach(s -> skills.add(s.name().toLowerCase(Locale.ROOT)));
        for (String requirement : job.requirements()) {
            String r = requirement.toLowerCase(Locale.ROOT).trim();
            if (r.length() < 2 || skills.contains(r) || containsTerm(source, r)) continue;
            if (containsTerm(lower, r)) {
                findings.add(new Finding("REQUIREMENT_WITHOUT_EVIDENCE", Severity.WARNING, Kind.HEURISTIC, requirement,
                        "\"" + requirement + "\" is a requirement of the job, and your profile has no evidence for it. "
                                + "Make sure the text does not present it as your skill or experience."));
            }
        }
    }

    private static boolean containsTerm(String haystack, String term) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(term.trim()) + "(?![a-z0-9])").matcher(haystack).find();
    }

    private static String sourceText(CandidateFacts facts) {
        StringBuilder b = new StringBuilder(Objects.toString(facts.professionalSummary(), "")).append('\n');
        facts.skills().forEach(s -> b.append(s.name()).append(' ').append(Objects.toString(s.category(), "")).append('\n'));
        facts.experiences().forEach(e -> {
            b.append(e.title()).append(' ').append(e.company()).append(' ').append(Objects.toString(e.location(), "")).append('\n');
            bullets(e.bullets()).forEach(t -> b.append(t).append('\n'));
        });
        facts.education().forEach(e -> b.append(e.qualification()).append(' ').append(e.institution()).append(' ')
                .append(Objects.toString(e.field(), "")).append(' ').append(Objects.toString(e.grade(), "")).append('\n'));
        facts.projects().forEach(p -> {
            b.append(p.name()).append(' ').append(Objects.toString(p.summary(), "")).append('\n');
            bullets(p.bullets()).forEach(t -> b.append(t).append('\n'));
        });
        facts.certifications().forEach(c -> b.append(c.name()).append(' ').append(Objects.toString(c.issuer(), "")).append('\n'));
        return b.toString();
    }

    /** Bullet text from the stored {@code [{"text": ...}]} shape. */
    public static List<String> bullets(List<Map<String, Object>> bullets) {
        if (bullets == null) return List.of();
        return bullets.stream().map(b -> b == null ? null : b.get("text")).filter(Objects::nonNull)
                .map(String::valueOf).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
