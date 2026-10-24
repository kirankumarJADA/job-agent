package com.personal.jobagent.automation;

import com.personal.jobagent.ats.AtsAdapter.FormFieldDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic Lever field classification (Phase 6).
 *
 * <p>Mirrors {@link GreenhouseFieldMapper}'s classification contract exactly
 * (same {@code SUPPORTED_AUTO}/{@code SUPPORTED_WITH_REVIEW}/{@code
 * REQUIRES_HUMAN}/{@code UNSUPPORTED} vocabulary, same "never invent a
 * value" rule, same protected-characteristic and work-authorisation
 * handling), applied to Lever's actual field keys, which differ from
 * Greenhouse's:
 *
 * <ul>
 *   <li>{@code name} — Lever uses one full-name field, not split first/last,
 *       so (unlike Greenhouse) no name-splitting ambiguity exists here.</li>
 *   <li>{@code email}, {@code phone}, {@code location}, {@code resume} —
 *       same meaning as Greenhouse's equivalents.</li>
 *   <li>{@code org} — the candidate's current employer. Robin has no
 *       verified source for this today, so it always requires a human.</li>
 *   <li>{@code urls[LinkedIn]}, {@code urls[GitHub]}, {@code
 *       urls[Portfolio]}, {@code urls[Twitter]} — filled from the same
 *       {@code profiles.links} map Greenhouse's "portfolio" style fields
 *       would use, matched case-insensitively by platform name.</li>
 *   <li>{@code comments} — Lever's single free-text field for a cover
 *       letter or anything else; filled with the approved cover letter body
 *       (plain text) under the same {@code SUPPORTED_WITH_REVIEW}
 *       classification Greenhouse's {@code cover_letter} upload uses, since
 *       it is a rendition that must be reviewed at the approval gate.</li>
 *   <li>{@code cards[...][fieldN]} — per-posting custom questions, classified
 *       by their rendered question text with the same demographic /
 *       work-authorisation / sponsorship / generic-answer precedence
 *       {@link GreenhouseFieldMapper} already uses.</li>
 * </ul>
 */
public final class LeverFieldMapper {

    public static final String AUTO = GreenhouseFieldMapper.AUTO;
    public static final String REVIEW = GreenhouseFieldMapper.REVIEW;
    public static final String HUMAN = GreenhouseFieldMapper.HUMAN;
    public static final String UNSUPPORTED = GreenhouseFieldMapper.UNSUPPORTED;

    /** Candidate data the mapper may reference — nothing else. */
    public record CandidateData(
            String email,
            String fullName,
            String phone,
            String location,
            Map<String, Object> links,
            boolean cvAvailable,
            boolean coverLetterAvailable,
            String coverLetterMarkdown
    ) {}

    public record Answer(UUID id, String questionText, String answerText, String status, boolean humanConfirmed) {}

    public record MappedField(FormFieldDescriptor field, String classification, String value,
                              String valueSource, String reason) {}

    public record HumanItem(String key, String label, String classification, String reason) {}

    public record FieldOutcome(String classification, String value, String valueSource, String reason) {}

    public record MappingResult(List<MappedField> mapped, List<HumanItem> humanRequired, List<HumanItem> unsupported) {}

    private LeverFieldMapper() {}

    public static MappingResult classify(List<FormFieldDescriptor> fields, CandidateData candidate, List<Answer> answers) {
        if (fields == null || candidate == null) throw new IllegalArgumentException("fields and candidate are required");
        List<MappedField> mapped = new ArrayList<>();
        List<HumanItem> humanRequired = new ArrayList<>();
        List<HumanItem> unsupported = new ArrayList<>();
        for (FormFieldDescriptor field : fields) {
            FieldOutcome outcome = classifyField(field, candidate, answers);
            switch (outcome.classification()) {
                case AUTO -> mapped.add(new MappedField(field, AUTO, outcome.value(), outcome.valueSource(), outcome.reason()));
                case REVIEW -> mapped.add(new MappedField(field, REVIEW, outcome.value(), outcome.valueSource(), outcome.reason()));
                case HUMAN -> humanRequired.add(new HumanItem(field.key(), field.label(), HUMAN, outcome.reason()));
                default -> unsupported.add(new HumanItem(field.key(), field.label(), UNSUPPORTED, outcome.reason()));
            }
        }
        return new MappingResult(mapped, humanRequired, unsupported);
    }

    public static FieldOutcome classifyField(FormFieldDescriptor field, CandidateData candidate, List<Answer> answers) {
        if (field == null || candidate == null) throw new IllegalArgumentException("field and candidate are required");
        String key = field.key() == null ? "" : field.key();
        String normalizedLabel = normalize(field.label());

        String demographic = demographicReason(normalizedLabel);
        if (demographic != null) return new FieldOutcome(UNSUPPORTED, null, null, demographic);

        switch (key) {
            case "name":
                if (isPresent(candidate.fullName())) return new FieldOutcome(AUTO, candidate.fullName(), "users.display_name", null);
                return new FieldOutcome(HUMAN, null, null, "no display name on the account");
            case "email":
                if (isPresent(candidate.email())) return new FieldOutcome(AUTO, candidate.email(), "users.email", null);
                return new FieldOutcome(HUMAN, null, null, "no verified email on the account");
            case "phone":
                if (isPresent(candidate.phone())) return new FieldOutcome(AUTO, candidate.phone(), "profiles.phone", null);
                return new FieldOutcome(HUMAN, null, null, "no phone number on the profile");
            case "location":
                if (isPresent(candidate.location())) return new FieldOutcome(AUTO, candidate.location(), "profiles.location", null);
                return new FieldOutcome(HUMAN, null, null, "no location on the profile");
            case "resume":
                if (candidate.cvAvailable()) return new FieldOutcome(AUTO, null, "cv_versions/files", null);
                return new FieldOutcome(HUMAN, null, null, "no tailored CV artifact for this application");
            case "comments":
                if (candidate.coverLetterAvailable() && isPresent(candidate.coverLetterMarkdown())) {
                    return new FieldOutcome(REVIEW, candidate.coverLetterMarkdown(), "cover_letters.body_markdown",
                            "filled with the approved cover letter — review content at approval");
                }
                return new FieldOutcome(HUMAN, null, null, "no cover letter generated for this application");
            case "org":
                return new FieldOutcome(HUMAN, null, null, "current employer is not a field Robin tracks");
            case "urls[LinkedIn]": return linkOutcome(candidate, "linkedin");
            case "urls[GitHub]": return linkOutcome(candidate, "github");
            case "urls[Portfolio]": return linkOutcome(candidate, "portfolio");
            case "urls[Twitter]": return linkOutcome(candidate, "twitter");
            default: break;
        }

        // Custom per-posting question (cards[...][fieldN]).
        if (isWorkAuthorizationQuestion(normalizedLabel)) {
            String answered = answeredValue(answers, normalizedLabel);
            if (answered != null) return new FieldOutcome(AUTO, answered, "application_answers (human-confirmed)", null);
            return new FieldOutcome(HUMAN, null, null, "work-authorisation question without a stored verified answer");
        }
        if (normalizedLabel.contains("sponsorship") || normalizedLabel.contains("sponsor")) {
            String answered = answeredValue(answers, normalizedLabel);
            if (answered != null) return new FieldOutcome(AUTO, answered, "application_answers (human-confirmed)", null);
            return new FieldOutcome(HUMAN, null, null, "sponsorship question — policy data is not an answer");
        }
        String answered = answeredValue(answers, normalizedLabel);
        if (answered != null) return new FieldOutcome(AUTO, answered, "application_answers (human-confirmed)", null);
        return new FieldOutcome(HUMAN, null, null, "unknown question — no verified answer exists");
    }

    private static FieldOutcome linkOutcome(CandidateData candidate, String platform) {
        if (candidate.links() == null) return new FieldOutcome(HUMAN, null, null, "no " + platform + " link on the profile");
        for (var entry : candidate.links().entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).contains(platform)
                    && entry.getValue() instanceof String url && isPresent(url)) {
                return new FieldOutcome(AUTO, url, "profiles.links", null);
            }
        }
        return new FieldOutcome(HUMAN, null, null, "no " + platform + " link on the profile");
    }

    private static String demographicReason(String normalizedLabel) {
        for (String marker : List.of("gender", "transgender", "sexual orientation", "disability",
                "neurodivergent", "veteran", "race", "ethnic")) {
            if (normalizedLabel.contains(marker)) {
                return "demographic/protected question — Robin does not answer these automatically";
            }
        }
        return null;
    }

    private static boolean isWorkAuthorizationQuestion(String normalizedLabel) {
        return normalizedLabel.contains("authorized to work") || normalizedLabel.contains("authorised to work")
                || normalizedLabel.contains("work authorization") || normalizedLabel.contains("work authorisation")
                || normalizedLabel.contains("legally authorized") || normalizedLabel.contains("legally authorised")
                || normalizedLabel.contains("right to work");
    }

    private static String answeredValue(List<Answer> answers, String normalizedQuestion) {
        if (answers == null) return null;
        List<Answer> matches = answers.stream()
                .filter(a -> a.questionText() != null && normalize(a.questionText()).equals(normalizedQuestion))
                .toList();
        if (matches.size() != 1 || !"ANSWERED".equals(matches.get(0).status())
                || !matches.get(0).humanConfirmed() || !isPresent(matches.get(0).answerText())) return null;
        return matches.get(0).answerText();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
