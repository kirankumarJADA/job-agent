package com.personal.jobagent.automation;

import com.personal.jobagent.ats.AtsAdapter.FormFieldDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic Greenhouse field classification (Phase 3B).
 *
 * <p>Maps each inspected Greenhouse field to the candidate data that exists in
 * Robin, and classifies every field into exactly one of:
 *
 * <ul>
 *   <li>{@code SUPPORTED_AUTO} — verified candidate data maps 1:1; the worker
 *       fills it automatically.</li>
 *   <li>{@code SUPPORTED_WITH_REVIEW} — filled automatically from verified
 *       data, but the representation is a rendition (e.g. cover letter
 *       markdown as .txt) and MUST be reviewed by the human at the approval
 *       gate.</li>
 *   <li>{@code REQUIRES_HUMAN} — no deterministic mapping; the human provides
 *       the value.</li>
 *   <li>{@code UNSUPPORTED} — sensitive/protected questions (demographics)
 *       Robin never answers automatically, even when an answer exists.</li>
 * </ul>
 *
 * <p>Pure and deterministic: same inputs → same classification. No value is
 * ever invented; {@code AUTO} values carry the exact source they came from.
 */
public final class GreenhouseFieldMapper {

    public static final String AUTO = "SUPPORTED_AUTO";
    public static final String REVIEW = "SUPPORTED_WITH_REVIEW";
    public static final String HUMAN = "REQUIRES_HUMAN";
    public static final String UNSUPPORTED = "UNSUPPORTED";

    /** Candidate data the mapper may reference — nothing else. */
    public record CandidateData(
            String email,
            String fullName,
            String phone,
            String location,
            Map<String, Object> links,
            Map<String, Object> workEligibility,
            boolean cvAvailable,
            UUID cvVersionId,
            String cvSha256,
            boolean coverLetterAvailable,
            UUID coverLetterId,
            String coverLetterMarkdown
    ) {}

    public record Answer(UUID id, String questionText, String answerText, String status) {}

    public record MappedField(
            FormFieldDescriptor field,
            String classification,
            String value,
            String valueSource,
            String reason
    ) {}

    public record HumanItem(String key, String label, String classification, String reason) {}

    public record MappingResult(List<MappedField> mapped, List<HumanItem> humanRequired,
                                List<HumanItem> unsupported, boolean cvMapped, boolean coverLetterMapped) {}

    private GreenhouseFieldMapper() {}

    public static MappingResult classify(List<FormFieldDescriptor> fields, CandidateData candidate,
                                         List<Answer> answers) {
        List<MappedField> mapped = new ArrayList<>();
        List<HumanItem> humanRequired = new ArrayList<>();
        List<HumanItem> unsupported = new ArrayList<>();
        boolean cvMapped = false;
        boolean coverLetterMapped = false;

        NameSplit name = splitName(candidate.fullName());
        Map<String, String> answerByNormalizedText = new LinkedHashMap<>();
        if (answers != null) {
            for (Answer answer : answers) {
                if ("ANSWERED".equals(answer.status()) && answer.questionText() != null) {
                    answerByNormalizedText.put(normalize(answer.questionText()), answer.answerText());
                }
            }
        }

        for (FormFieldDescriptor field : fields) {
            String key = field.key() == null ? "" : field.key().toLowerCase(Locale.ROOT);
            String label = field.label() == null ? "" : field.label();
            String normalizedLabel = normalize(label);

            switch (key) {
                case "first_name" -> {
                    if (name.deterministic()) {
                        mapped.add(new MappedField(field, AUTO, name.firstName(), "users.display_name", "deterministic two-token name split"));
                    } else {
                        humanRequired.add(new HumanItem(field.key(), label, HUMAN, "display name does not split deterministically into first/last name"));
                    }
                    continue;
                }
                case "last_name" -> {
                    if (name.deterministic()) {
                        mapped.add(new MappedField(field, AUTO, name.lastName(), "users.display_name", "deterministic two-token name split"));
                    } else {
                        humanRequired.add(new HumanItem(field.key(), label, HUMAN, "display name does not split deterministically into first/last name"));
                    }
                    continue;
                }
                case "email" -> {
                    if (candidate.email() != null && !candidate.email().isBlank()) {
                        mapped.add(new MappedField(field, AUTO, candidate.email(), "users.email", null));
                    } else {
                        humanRequired.add(new HumanItem(field.key(), label, HUMAN, "no verified email on the account"));
                    }
                    continue;
                }
                case "phone" -> {
                    if (candidate.phone() != null && !candidate.phone().isBlank()) {
                        mapped.add(new MappedField(field, AUTO, candidate.phone(), "profiles.phone", null));
                    } else {
                        humanRequired.add(new HumanItem(field.key(), label, HUMAN, "no phone number on the profile"));
                    }
                    continue;
                }
                case "resume" -> {
                    if (candidate.cvAvailable()) {
                        mapped.add(new MappedField(field, AUTO, null, "cv_versions/files", null));
                        cvMapped = true;
                    } else {
                        humanRequired.add(new HumanItem(field.key(), label, HUMAN, "no tailored CV artifact for this application"));
                    }
                    continue;
                }
                case "cover_letter" -> {
                    if (candidate.coverLetterAvailable() && candidate.coverLetterMarkdown() != null) {
                        mapped.add(new MappedField(field, REVIEW, null, "cover_letters.body_markdown",
                                "uploaded as plain-text rendition of the approved markdown — review content at approval"));
                        coverLetterMapped = true;
                    } else {
                        humanRequired.add(new HumanItem(field.key(), label, REVIEW, "no cover letter generated for this application"));
                    }
                    continue;
                }
                default -> { }
            }

            // Location-style free-text fields with a profile source.
            if (key.equals("candidate-location") || key.equals("location")) {
                if (candidate.location() != null && !candidate.location().isBlank()) {
                    mapped.add(new MappedField(field, AUTO, candidate.location(), "profiles.location", null));
                } else {
                    humanRequired.add(new HumanItem(field.key(), label, HUMAN, "no location on the profile"));
                }
                continue;
            }

            // Demographics / protected characteristics — never auto-filled.
            String demographic = demographicReason(normalizedLabel);
            if (demographic != null) {
                unsupported.add(new HumanItem(field.key(), label, UNSUPPORTED, demographic));
                continue;
            }

            // Work authorisation: only an explicit ANSWERED answer maps.
            if (isWorkAuthorizationQuestion(normalizedLabel)) {
                String answered = answerByNormalizedText.get(normalizedLabel);
                if (answered != null) {
                    mapped.add(new MappedField(field, AUTO, answered, "application_answers (ANSWERED)", null));
                } else {
                    humanRequired.add(new HumanItem(field.key(), label, HUMAN, "work-authorisation question without a stored verified answer"));
                }
                continue;
            }

            // Sponsorship: never inferred from a preference policy.
            if (normalizedLabel.contains("sponsorship") || normalizedLabel.contains("sponsor")) {
                String answered = answerByNormalizedText.get(normalizedLabel);
                if (answered != null) {
                    mapped.add(new MappedField(field, AUTO, answered, "application_answers (ANSWERED)", null));
                } else {
                    humanRequired.add(new HumanItem(field.key(), label, HUMAN, "sponsorship question — policy data is not an answer"));
                }
                continue;
            }

            // Generic questions: only an ANSWERED application answer maps.
            String answered = answerByNormalizedText.get(normalizedLabel);
            if (answered != null) {
                mapped.add(new MappedField(field, AUTO, answered, "application_answers (ANSWERED)", null));
            } else if (answerHasStatus(answers, normalizedLabel, "NEEDS_USER_INPUT")) {
                humanRequired.add(new HumanItem(field.key(), label, HUMAN, "draft answer needs user input"));
            } else if (answerHasStatus(answers, normalizedLabel, "HARD_STOP")) {
                humanRequired.add(new HumanItem(field.key(), label, "HARD_STOP", "draft answer was hard-stopped"));
            } else {
                humanRequired.add(new HumanItem(field.key(), label, HUMAN, "unknown question — no verified answer exists"));
            }
        }

        return new MappingResult(mapped, humanRequired, unsupported, cvMapped, coverLetterMapped);
    }

    private static boolean answerHasStatus(List<Answer> answers, String normalizedQuestion, String status) {
        if (answers == null) return false;
        return answers.stream().anyMatch(a ->
                a.questionText() != null && normalize(a.questionText()).equals(normalizedQuestion)
                        && status.equals(a.status()));
    }

    /** Protected-characteristic labels are never auto-filled. */
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

    record NameSplit(boolean deterministic, String firstName, String lastName) {}

    /** Deterministic only for exactly two non-empty tokens; anything else is human review. */
    static NameSplit splitName(String fullName) {
        if (fullName == null) return new NameSplit(false, null, null);
        List<String> tokens = new ArrayList<>(List.of(fullName.trim().split("\\s+")));
        tokens.removeIf(String::isEmpty);
        if (tokens.size() != 2) return new NameSplit(false, null, null);
        return new NameSplit(true, tokens.get(0), tokens.get(1));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
