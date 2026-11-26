package com.personal.jobagent.automation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared canonical field mapper and fact snapshot builder for universal
 * employer websites ({@code planType = "UNIVERSAL"}).
 *
 * <p>Operates strictly over verified candidate data and explicitly
 * candidate-confirmed {@code application_answers}. Never fabricates values,
 * never auto-fills protected/EEO characteristics, never infers work
 * authorization or visa sponsorship from profile policy metadata, and never
 * bypasses ambiguous name splits.
 */
public final class UniversalFieldMapper {

    public static final String AUTO = "SUPPORTED_AUTO";
    public static final String REVIEW = "SUPPORTED_WITH_REVIEW";
    public static final String HUMAN = "REQUIRES_HUMAN";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String HARD_STOP = "HARD_STOP";
    public static final String OPTIONAL_SKIP = "OPTIONAL_SKIP";

    private static final int MAX_TEXT = 300;

    private static final Set<String> NON_REUSABLE_QUESTION_TYPES = Set.of("WHY_COMPANY", "WHY_ROLE");

    public record VerifiedAnswer(
            UUID id,
            UUID applicationId,
            String questionText,
            String questionType,
            String answerText,
            String status,
            boolean humanConfirmed,
            String answerOrigin,
            boolean reusable
    ) {
        /** True only when candidate explicitly confirmed or edited this answer (never raw MODEL_DRAFT). */
        public boolean trustedForAutofill() {
            return "ANSWERED".equals(status)
                    && humanConfirmed
                    && ("CANDIDATE_CONFIRMED".equals(answerOrigin) || "CANDIDATE_EDITED".equals(answerOrigin))
                    && answerText != null && !answerText.isBlank();
        }
    }

    public record CandidateFactSnapshot(
            String email,
            String displayName,
            String phone,
            String location,
            Map<String, Object> links,
            boolean cvAvailable,
            UUID cvVersionId,
            String cvSha256,
            String cvFileName,
            boolean coverLetterAvailable,
            UUID coverLetterId,
            String coverLetterMarkdown,
            List<VerifiedAnswer> scopedAnswers,
            List<VerifiedAnswer> reusableAnswers
    ) {
        public CandidateFactSnapshot {
            links = links == null ? Map.of() : links;
            scopedAnswers = scopedAnswers == null ? List.of() : scopedAnswers;
            reusableAnswers = reusableAnswers == null ? List.of() : reusableAnswers;
        }
    }

    public record DiscoveredControl(
            String controlId,
            String tagName,
            String htmlType,
            String role,
            String label,
            String nameAttr,
            String idAttr,
            String placeholder,
            String ariaLabel,
            boolean required,
            List<String> options
    ) {
        public DiscoveredControl {
            options = options == null ? List.of() : options;
        }
    }

    public record MappedControl(
            String controlId,
            String semanticKey,
            String classification,
            String value,
            String valueSource,
            String artifactKind,
            String reason
    ) {}

    private UniversalFieldMapper() {}

    /**
     * Builds the non-secret, worker-safe canonical profile facts object embedded
     * inside a {@code UNIVERSAL} plan package. Zero passwords, zero raw drafts,
     * zero EEO/demographic attributes, zero synthetic sponsorship answers.
     */
    public static Map<String, Object> buildProfileFactsPayload(CandidateFactSnapshot snapshot) {
        Map<String, Object> facts = new LinkedHashMap<>();
        GreenhouseFieldMapper.NameSplit split = GreenhouseFieldMapper.splitName(snapshot.displayName());
        putIfPresent(facts, "fullName", snapshot.displayName());
        if (split.deterministic()) {
            putIfPresent(facts, "firstName", split.firstName());
            putIfPresent(facts, "lastName", split.lastName());
        }
        putIfPresent(facts, "email", snapshot.email());
        putIfPresent(facts, "phone", snapshot.phone());
        putIfPresent(facts, "location", snapshot.location());
        putIfPresent(facts, "linkedin", extractLink(snapshot.links(), "linkedin", "linked_in"));
        putIfPresent(facts, "github", extractLink(snapshot.links(), "github"));
        putIfPresent(facts, "portfolio", extractLink(snapshot.links(), "portfolio", "website", "url", "personal_site"));
        if (snapshot.coverLetterAvailable() && snapshot.coverLetterMarkdown() != null && !snapshot.coverLetterMarkdown().isBlank()) {
            facts.put("coverLetterText", snapshot.coverLetterMarkdown());
        }
        return facts;
    }

    /**
     * Collects candidate-confirmed answers authorized for this application,
     * keeping application-scoped answers ahead of job-scoped fallbacks, and
     * explicitly validated reusable answers only for non-essay factual questions.
     */
    public static List<Map<String, Object>> buildConfirmedAnswersPayload(CandidateFactSnapshot snapshot) {
        Map<String, VerifiedAnswer> byNormalizedQuestion = new LinkedHashMap<>();
        Set<String> blockedOrUnconfirmed = new LinkedHashSet<>();

        for (VerifiedAnswer a : snapshot.scopedAnswers()) {
            String key = normalize(a.questionText());
            if (key.isEmpty()) continue;
            if ("HARD_STOP".equals(a.status()) || "NEEDS_USER_INPUT".equals(a.status())) {
                blockedOrUnconfirmed.add(key);
                byNormalizedQuestion.remove(key);
                continue;
            }
            if (a.trustedForAutofill() && !isProtectedLabel(key)) {
                if (a.applicationId() != null || !byNormalizedQuestion.containsKey(key)) {
                    byNormalizedQuestion.put(key, a);
                }
            }
        }
        for (VerifiedAnswer a : snapshot.reusableAnswers()) {
            String key = normalize(a.questionText());
            if (key.isEmpty() || blockedOrUnconfirmed.contains(key) || byNormalizedQuestion.containsKey(key)) {
                continue;
            }
            if (a.reusable() && a.trustedForAutofill()
                    && (a.questionType() == null || !NON_REUSABLE_QUESTION_TYPES.contains(a.questionType()))
                    && !isProtectedLabel(key)) {
                byNormalizedQuestion.put(key, a);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (VerifiedAnswer a : byNormalizedQuestion.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (a.id() != null) row.put("answerId", a.id().toString());
            row.put("questionText", sanitizeText(a.questionText()));
            row.put("questionType", a.questionType() == null ? "OPEN_ENDED" : a.questionType());
            row.put("answerText", a.answerText());
            row.put("source", a.applicationId() == null && a.reusable()
                    ? "application_answers (candidate-confirmed reusable)"
                    : "application_answers (human-confirmed)");
            out.add(row);
        }
        return out;
    }

    /** Deterministic field classifier mirroring live DOM worker logic in pure Java for verification. */
    public static MappedControl classifyControl(DiscoveredControl control, CandidateFactSnapshot snapshot) {
        if (control == null || snapshot == null) {
            throw new IllegalArgumentException("control and snapshot are required");
        }
        String htmlType = control.htmlType() == null ? "text" : control.htmlType().toLowerCase(Locale.ROOT);
        String label = sanitizeText(control.label());
        String combined = normalize(String.join(" ",
                label,
                sanitizeText(control.ariaLabel()),
                sanitizeText(control.placeholder()),
                sanitizeText(control.nameAttr()),
                sanitizeText(control.idAttr())));

        if ("password".equals(htmlType) || combined.contains("password") || combined.contains("one-time")
                || combined.contains("otp") || combined.contains("verification code") || combined.contains("captcha")) {
            return new MappedControl(control.controlId(), "auth_secret", HARD_STOP, null, null, null,
                    "authentication/credential/security control must never be auto-filled by form mapper");
        }
        if (isProtectedLabel(normalize(label)) || isProtectedLabel(combined)) {
            return new MappedControl(control.controlId(), "eeo_protected", UNSUPPORTED, null, null, null,
                    "demographic/EEO question is never answered automatically");
        }
        if ("file".equals(htmlType)) {
            if (combined.contains("cover") || combined.contains("letter")) {
                if (snapshot.coverLetterAvailable()) {
                    return new MappedControl(control.controlId(), "cover_letter", REVIEW, null,
                            "cover_letters.body_markdown", "coverLetter", null);
                }
                return new MappedControl(control.controlId(), "cover_letter",
                        control.required() ? HUMAN : OPTIONAL_SKIP, null, null, null,
                        "no approved cover letter artifact for this application");
            }
            if (combined.contains("resume") || combined.contains("cv") || combined.contains("curriculum")) {
                if (snapshot.cvAvailable()) {
                    return new MappedControl(control.controlId(), "resume", AUTO, null,
                            "cv_versions/files", "cv", null);
                }
                return new MappedControl(control.controlId(), "resume",
                        control.required() ? HUMAN : OPTIONAL_SKIP, null, null, null,
                        "no reviewed CV artifact for this application");
            }
            return new MappedControl(control.controlId(), "other_file",
                    control.required() ? HUMAN : OPTIONAL_SKIP, null, null, null,
                    "unrecognized required file upload requires human action");
        }

        // Exact Q&A match (checked before generic profile heuristics, especially for sensitive questions)
        String normalizedLabel = normalize(label);
        if (!normalizedLabel.isEmpty()) {
            for (VerifiedAnswer scoped : snapshot.scopedAnswers()) {
                if (normalize(scoped.questionText()).equals(normalizedLabel)) {
                    if ("HARD_STOP".equals(scoped.status())) {
                        return new MappedControl(control.controlId(), "question", HARD_STOP, null, null, null,
                                "answer was hard-stopped for factual validation");
                    }
                    if ("NEEDS_USER_INPUT".equals(scoped.status()) || !scoped.trustedForAutofill()) {
                        return new MappedControl(control.controlId(), "question",
                                control.required() ? HUMAN : OPTIONAL_SKIP, null, null, null,
                                "answer is unconfirmed or requires candidate input");
                    }
                    return resolveTextOrOption(control, "question", scoped.answerText(),
                            "application_answers (human-confirmed)");
                }
            }
            if (isSensitiveLegalLabel(normalizedLabel)) {
                return resolveReusableOrGap(control, normalizedLabel, snapshot, true);
            }
        }

        if (isFirstName(combined)) {
            var split = GreenhouseFieldMapper.splitName(snapshot.displayName());
            if (split.deterministic()) {
                return new MappedControl(control.controlId(), "first_name", AUTO, split.firstName(),
                        "users.display_name", null, null);
            }
            return gapOrSkip(control, "first_name", "display name cannot split deterministically into first/last");
        }
        if (isLastName(combined)) {
            var split = GreenhouseFieldMapper.splitName(snapshot.displayName());
            if (split.deterministic()) {
                return new MappedControl(control.controlId(), "last_name", AUTO, split.lastName(),
                        "users.display_name", null, null);
            }
            return gapOrSkip(control, "last_name", "display name cannot split deterministically into first/last");
        }
        if (isFullName(combined)) {
            if (snapshot.displayName() != null && !snapshot.displayName().isBlank()) {
                return new MappedControl(control.controlId(), "full_name", AUTO, snapshot.displayName().trim(),
                        "users.display_name", null, null);
            }
            return gapOrSkip(control, "full_name", "missing verified display name");
        }
        if ("email".equals(htmlType) || combined.contains("email") || combined.contains("e-mail")) {
            if (snapshot.email() != null && !snapshot.email().isBlank()) {
                return new MappedControl(control.controlId(), "email", AUTO, snapshot.email().trim(),
                        "users.email", null, null);
            }
            return gapOrSkip(control, "email", "missing verified email");
        }
        if ("tel".equals(htmlType) || combined.contains("phone") || combined.contains("mobile")) {
            if (snapshot.phone() != null && !snapshot.phone().isBlank()) {
                return new MappedControl(control.controlId(), "phone", AUTO, snapshot.phone().trim(),
                        "profiles.phone", null, null);
            }
            return gapOrSkip(control, "phone", "missing phone on profile");
        }
        if (combined.contains("linkedin")) {
            String url = extractLink(snapshot.links(), "linkedin", "linked_in");
            return url != null
                    ? new MappedControl(control.controlId(), "linkedin", AUTO, url, "profiles.links.linkedin", null, null)
                    : gapOrSkip(control, "linkedin", "missing verified LinkedIn link");
        }
        if (combined.contains("github")) {
            String url = extractLink(snapshot.links(), "github");
            return url != null
                    ? new MappedControl(control.controlId(), "github", AUTO, url, "profiles.links.github", null, null)
                    : gapOrSkip(control, "github", "missing verified GitHub link");
        }
        if (combined.contains("portfolio") || combined.contains("website") || combined.contains("personal site")) {
            String url = extractLink(snapshot.links(), "portfolio", "website", "url", "personal_site");
            return url != null
                    ? new MappedControl(control.controlId(), "portfolio", AUTO, url, "profiles.links.portfolio", null, null)
                    : gapOrSkip(control, "portfolio", "missing verified website/portfolio link");
        }
        if (combined.equals("location") || combined.contains("city") || combined.contains("current location")
                || combined.contains("where are you based")) {
            if (snapshot.location() != null && !snapshot.location().isBlank()) {
                return new MappedControl(control.controlId(), "location", AUTO, snapshot.location().trim(),
                        "profiles.location", null, null);
            }
            return gapOrSkip(control, "location", "missing location on profile");
        }
        if ((combined.contains("cover letter") || combined.contains("cover_letter"))
                && "textarea".equalsIgnoreCase(control.tagName())) {
            if (snapshot.coverLetterAvailable() && snapshot.coverLetterMarkdown() != null && !snapshot.coverLetterMarkdown().isBlank()) {
                return new MappedControl(control.controlId(), "cover_letter_text", REVIEW, snapshot.coverLetterMarkdown(),
                        "cover_letters.body_markdown", null, null);
            }
            return gapOrSkip(control, "cover_letter_text", "missing approved cover letter");
        }

        return resolveReusableOrGap(control, normalizedLabel, snapshot, false);
    }

    private static MappedControl resolveReusableOrGap(DiscoveredControl control,
                                                      String normalizedLabel,
                                                      CandidateFactSnapshot snapshot,
                                                      boolean sensitiveLegal) {
        if (!normalizedLabel.isEmpty()) {
            for (VerifiedAnswer a : snapshot.reusableAnswers()) {
                if (a.reusable() && a.trustedForAutofill()
                        && (a.questionType() == null || !NON_REUSABLE_QUESTION_TYPES.contains(a.questionType()))
                        && normalize(a.questionText()).equals(normalizedLabel)) {
                    return resolveTextOrOption(control, "question", a.answerText(),
                            "application_answers (candidate-confirmed reusable)");
                }
            }
        }
        String reason = sensitiveLegal
                ? "work-authorization / sponsorship question requires explicit candidate-confirmed answer"
                : "unknown field without verified fact or confirmed answer";
        return gapOrSkip(control, "question", reason);
    }

    private static MappedControl resolveTextOrOption(DiscoveredControl control,
                                                       String semanticKey,
                                                       String answerText,
                                                       String source) {
        String type = control.htmlType() == null ? "text" : control.htmlType().toLowerCase(Locale.ROOT);
        if ("select".equals(type) || "radio".equals(type) || "select-one".equals(type)) {
            String trimmed = answerText.trim();
            for (String opt : control.options()) {
                if (opt != null && opt.trim().equalsIgnoreCase(trimmed)) {
                    return new MappedControl(control.controlId(), semanticKey, AUTO, opt.trim(), source, null, null);
                }
            }
            return gapOrSkip(control, semanticKey, "confirmed answer does not match available form options");
        }
        return new MappedControl(control.controlId(), semanticKey, AUTO, answerText.trim(), source, null, null);
    }

    private static MappedControl gapOrSkip(DiscoveredControl control, String key, String reason) {
        return new MappedControl(control.controlId(), key,
                control.required() ? HUMAN : OPTIONAL_SKIP, null, null, null, reason);
    }

    static boolean isProtectedLabel(String normalized) {
        for (String marker : List.of("gender", "transgender", "sexual orientation", "disability",
                "neurodivergent", "veteran", "race", "ethnic", "hispanic", "latino", "pronoun", "marital")) {
            if (normalized.contains(marker)) return true;
        }
        return false;
    }

    static boolean isSensitiveLegalLabel(String normalized) {
        return normalized.contains("authorized to work") || normalized.contains("authorised to work")
                || normalized.contains("legally authorized") || normalized.contains("legally authorised")
                || normalized.contains("work authorization") || normalized.contains("work authorisation")
                || normalized.contains("right to work") || normalized.contains("sponsorship")
                || normalized.contains("visa");
    }

    private static boolean isFirstName(String c) {
        return c.contains("first name") || c.contains("first_name") || c.contains("firstname")
                || c.contains("given name") || c.contains("given_name") || c.contains("forename");
    }

    private static boolean isLastName(String c) {
        return c.contains("last name") || c.contains("last_name") || c.contains("lastname")
                || c.contains("family name") || c.contains("family_name") || c.contains("surname");
    }

    private static boolean isFullName(String c) {
        if (c.contains("company") || c.contains("employer") || c.contains("school")
                || c.contains("university") || c.contains("referral") || c.contains("manager")) {
            return false;
        }
        return c.contains("full name") || c.contains("full_name") || c.equals("name") || c.contains("your name");
    }

    static String extractLink(Map<String, Object> links, String... keys) {
        if (links == null || links.isEmpty()) return null;
        for (String k : keys) {
            for (var entry : links.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(k) && entry.getValue() != null) {
                    String v = String.valueOf(entry.getValue()).trim();
                    if (!v.isBlank() && (v.startsWith("https://") || v.startsWith("http://"))) {
                        return v;
                    }
                }
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    public static String sanitizeText(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("[\\p{Cntrl}&&[^\n\t ]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > MAX_TEXT ? cleaned.substring(0, MAX_TEXT) : cleaned;
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        return sanitizeText(raw)
                .replaceAll("\\s*\\*\\s*$", "")
                .replaceAll("\\s*\\(required\\)\\s*$", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
