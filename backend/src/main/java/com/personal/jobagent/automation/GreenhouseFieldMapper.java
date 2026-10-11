package com.personal.jobagent.automation;

import com.personal.jobagent.ats.AtsAdapter.FormFieldDescriptor;

import java.util.ArrayList;
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
    public static final String HARD_STOP = "HARD_STOP";

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

    public record Answer(UUID id, String questionText, String answerText, String status,
                         boolean humanConfirmed) {
        public Answer(UUID id, String questionText, String answerText, String status) {
            this(id, questionText, answerText, status, false);
        }
    }

    public record MappedField(
            FormFieldDescriptor field,
            String classification,
            String value,
            String valueSource,
            String reason
    ) {}

    public record HumanItem(String key, String label, String classification, String reason) {}

    public record FieldOutcome(String classification, String value, String valueSource, String reason) {}

    public record MappingResult(List<MappedField> mapped, List<HumanItem> humanRequired,
                                List<HumanItem> unsupported, boolean cvMapped, boolean coverLetterMapped) {}

    private GreenhouseFieldMapper() {}

    public static MappingResult classify(List<FormFieldDescriptor> fields, CandidateData candidate,
                                         List<Answer> answers) {
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
                case HARD_STOP -> humanRequired.add(new HumanItem(field.key(), field.label(), HARD_STOP, outcome.reason()));
                default -> unsupported.add(new HumanItem(field.key(), field.label(), UNSUPPORTED, outcome.reason()));
            }
        }
        boolean cvMapped = mapped.stream().anyMatch(m -> m.field().key().equalsIgnoreCase("resume"));
        boolean coverLetterMapped = mapped.stream().anyMatch(m -> m.field().key().equalsIgnoreCase("cover_letter"));
        return new MappingResult(mapped, humanRequired, unsupported, cvMapped, coverLetterMapped);
    }

    /**
     * Deterministic classification for ONE inspected field. Order matters:
     * exact key mappings first, then protected characteristics, then
     * work-authorization/sponsorship policy, then generic ANSWERED questions.
     */
    public static FieldOutcome classifyField(FormFieldDescriptor field, CandidateData candidate,
                                             List<Answer> answers) {
        if (field == null || candidate == null) throw new IllegalArgumentException("field and candidate are required");
        String key = field.key() == null ? "" : field.key().toLowerCase(Locale.ROOT);
        String normalizedLabel = normalize(field.label());

        // Never let an inspected/custom field key override the meaning of a
        // sensitive question label.
        String demographic = demographicReason(normalizedLabel);
        if (demographic != null) return new FieldOutcome(UNSUPPORTED, null, null, demographic);

        switch (key) {
            case "first_name": {
                NameSplit name = splitName(candidate.fullName());
                if (name.deterministic()) return new FieldOutcome(AUTO, name.firstName(), "users.display_name", null);
                return new FieldOutcome(HUMAN, null, null, "display name does not split deterministically into first/last name");
            }
            case "last_name": {
                NameSplit name = splitName(candidate.fullName());
                if (name.deterministic()) return new FieldOutcome(AUTO, name.lastName(), "users.display_name", null);
                return new FieldOutcome(HUMAN, null, null, "display name does not split deterministically into first/last name");
            }
            case "email": {
                if (isPresent(candidate.email())) return new FieldOutcome(AUTO, candidate.email(), "users.email", null);
                return new FieldOutcome(HUMAN, null, null, "no verified email on the account");
            }
            case "phone": {
                if (isPresent(candidate.phone())) return new FieldOutcome(AUTO, candidate.phone(), "profiles.phone", null);
                return new FieldOutcome(HUMAN, null, null, "no phone number on the profile");
            }
            case "resume": {
                if (candidate.cvAvailable()) return new FieldOutcome(AUTO, null, "cv_versions/files", null);
                return new FieldOutcome(HUMAN, null, null, "no tailored CV artifact for this application");
            }
            case "cover_letter": {
                if (candidate.coverLetterAvailable() && isPresent(candidate.coverLetterMarkdown())) {
                    return new FieldOutcome(REVIEW, null, "cover_letters.body_markdown",
                            "uploaded as plain-text rendition of the approved markdown — review content at approval");
                }
                return new FieldOutcome(HUMAN, null, null, "no cover letter generated for this application");
            }
            default: break;
        }

        if (key.equals("candidate-location") || key.equals("location")) {
            if (isPresent(candidate.location())) return new FieldOutcome(AUTO, candidate.location(), "profiles.location", null);
            return new FieldOutcome(HUMAN, null, null, "no location on the profile");
        }

        String answerStatus = answerStatus(answers, normalizedLabel);
        if ("HARD_STOP".equals(answerStatus)) {
            return new FieldOutcome(HARD_STOP, null, null, "draft answer was hard-stopped");
        }
        String answered = answeredValue(answers, normalizedLabel);
        if (isWorkAuthorizationQuestion(normalizedLabel)) {
            if (answered != null) return new FieldOutcome(AUTO, answered, "application_answers (human-confirmed)", null);
            return new FieldOutcome(HUMAN, null, null, "work-authorisation question without a stored verified answer");
        }

        if (normalizedLabel.contains("sponsorship") || normalizedLabel.contains("sponsor")) {
            if (answered != null) return new FieldOutcome(AUTO, answered, "application_answers (human-confirmed)", null);
            return new FieldOutcome(HUMAN, null, null, "sponsorship question — policy data is not an answer");
        }

        // answeredValue only returns ANSWERED, human-confirmed, non-empty
        // answers, so the provenance label is exactly the guarantee upstream
        // consumers check before trusting a question value.
        if (answered != null) return new FieldOutcome(AUTO, answered, "application_answers (human-confirmed)", null);
        if ("NEEDS_USER_INPUT".equals(answerStatus)) {
            return new FieldOutcome(HUMAN, null, null, "draft answer needs user input");
        }
        return new FieldOutcome(HUMAN, null, null, "unknown question — no verified answer exists");
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

    private static String answeredValue(List<Answer> answers, String normalizedQuestion) {
        if (answers == null) return null;
        List<Answer> matches = answers.stream()
                .filter(a -> a.questionText() != null && normalize(a.questionText()).equals(normalizedQuestion))
                .toList();
        if (matches.size() != 1 || !"ANSWERED".equals(matches.get(0).status())
                || !matches.get(0).humanConfirmed()
                || !isPresent(matches.get(0).answerText())) return null;

        return matches.get(0).answerText();
    }

    private static String answerStatus(List<Answer> answers, String normalizedQuestion) {
        if (answers == null) return null;
        List<String> statuses = answers.stream()
                .filter(a -> a.questionText() != null && normalize(a.questionText()).equals(normalizedQuestion))
                .map(Answer::status)
                .toList();
        if (statuses.contains("HARD_STOP")) return "HARD_STOP";
        if (statuses.size() == 1 && "NEEDS_USER_INPUT".equals(statuses.get(0))) return "NEEDS_USER_INPUT";
        return null;
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

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
