package com.personal.jobagent.track;

import com.personal.jobagent.discovery.platform.JobIdentity;
import com.personal.jobagent.track.TrackVocabulary.EmailCategory;
import com.personal.jobagent.track.TrackVocabulary.LinkStatus;
import com.personal.jobagent.track.TrackVocabulary.MatchStrength;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Proposes which of the owner's applications a message is about.
 *
 * <p><b>Only strong identity evidence links a message on its own</b>: a link to
 * the job's own posting URL, the posting's platform job identifier embedded in
 * a URL, or the job's requisition number quoted together with the employer's
 * name — the same keys {@link JobIdentity} uses to recognise a posting across
 * sources. Company name and job title are weak signals: they only ever produce
 * a PENDING_REVIEW proposal with the candidates listed, so two similar roles
 * (same title, same company) are never guessed between. A model or rule
 * confidence is not part of this decision at all.
 *
 * <p>The list of applications passed in is already restricted to the owner, so
 * a message can never be proposed for another candidate's application.
 */
public final class EmailApplicationMatcher {

    public static final String VERSION = "track-match-v1";
    private static final int MAX_CANDIDATES = 5;

    public record OwnedApplication(UUID applicationId, UUID jobId, String jobTitle, String company, String location,
                                   String applicationUrl, String canonicalUrl, Set<String> identityKeys,
                                   Instant createdAt, String recordedStatus) {
    }

    public record MessageEvidence(String from, String subject, String scanText,
                                  List<String> urls, List<String> requisitions) {

        public static MessageEvidence of(String from, String subject, String body) {
            String text = TrackText.scanText(subject, body);
            return new MessageEvidence(from, subject, text, TrackText.urls(text), TrackText.requisitionTokens(text));
        }
    }

    public record Candidate(UUID applicationId, List<String> signals) {
    }

    public record Association(LinkStatus status, MatchStrength strength, UUID applicationId,
                              List<Candidate> candidates, List<Map<String, String>> rationale,
                              List<String> identityEvidence, boolean identifiesOtherApplication) {

        public boolean established() {
            return applicationId != null && (status == LinkStatus.LINKED || status == LinkStatus.CONFIRMED);
        }
    }

    public Association match(MessageEvidence message, List<OwnedApplication> owned, UUID assertedApplicationId,
                             EmailCategory category) {
        Objects.requireNonNull(message, "message");
        List<OwnedApplication> apps = owned == null ? List.of() : owned;
        if (category == EmailCategory.VERIFICATION_CODE) {
            return new Association(LinkStatus.NOT_APPLICABLE, MatchStrength.NONE, null, List.of(),
                    List.of(reason("NOT_TRACKING_EVIDENCE", "A sign-in verification message is not application evidence.")),
                    List.of(), false);
        }

        String lowerText = TrackText.lower(TrackText.collapse(message.scanText()));
        String fromSquashed = TrackText.squash(message.from());
        Set<String> urlKeys = new LinkedHashSet<>();
        Set<String> atsKeys = new LinkedHashSet<>();
        for (String url : message.urls()) {
            String normalized = JobIdentity.normalizeUrl(url);
            if (normalized != null) urlKeys.add(normalized);
            String ats = JobIdentity.atsKey(url);
            if (ats != null) atsKeys.add(ats);
        }

        Map<UUID, List<String>> strong = new LinkedHashMap<>();
        Map<UUID, List<String>> weak = new LinkedHashMap<>();
        Map<UUID, String> requisitionByApp = new LinkedHashMap<>();
        for (OwnedApplication app : apps) {
            List<String> strongSignals = new ArrayList<>();
            List<String> weakSignals = new ArrayList<>();
            Set<String> keys = app.identityKeys() == null ? Set.of() : app.identityKeys();
            String appUrl = JobIdentity.normalizeUrl(app.applicationUrl());
            String canonical = JobIdentity.normalizeUrl(app.canonicalUrl());
            for (String key : urlKeys) {
                if (keys.contains("url:" + key) || key.equals(appUrl) || key.equals(canonical)) {
                    strongSignals.add("JOB_URL");
                    break;
                }
            }
            String appAts = JobIdentity.atsKey(app.applicationUrl());
            String canonicalAts = JobIdentity.atsKey(app.canonicalUrl());
            for (String key : atsKeys) {
                if (keys.contains("ats:" + key) || key.equals(appAts) || key.equals(canonicalAts)) {
                    strongSignals.add("POSTING_IDENTIFIER");
                    break;
                }
            }
            boolean companyNamed = companyNamed(app.company(), lowerText, fromSquashed);
            String employerKey = JobIdentity.employerKey(app.company());
            if (employerKey != null && companyNamed) {
                for (String token : message.requisitions()) {
                    if (keys.contains("req:" + employerKey + ":" + token)) {
                        strongSignals.add("REQUISITION_NUMBER");
                        requisitionByApp.put(app.applicationId(), token);
                        break;
                    }
                }
            }
            if (companyNamed) weakSignals.add("COMPANY_NAME");
            if (titleNamed(app.jobTitle(), lowerText)) weakSignals.add("JOB_TITLE");
            if (!strongSignals.isEmpty()) {
                List<String> all = new ArrayList<>(strongSignals);
                all.addAll(weakSignals);
                strong.put(app.applicationId(), all);
            } else if (!weakSignals.isEmpty()) {
                weak.put(app.applicationId(), weakSignals);
            }
        }

        // ── candidate assertion ("add this message to application X") ──────
        if (assertedApplicationId != null) {
            boolean assertedIsStrong = strong.containsKey(assertedApplicationId);
            boolean othersStrong = strong.keySet().stream().anyMatch(id -> !id.equals(assertedApplicationId));
            if (othersStrong && !assertedIsStrong) {
                List<Candidate> candidates = new ArrayList<>();
                candidates.add(new Candidate(assertedApplicationId, List.of("YOUR_CHOICE")));
                strong.forEach((id, signals) -> candidates.add(new Candidate(id, signals)));
                return new Association(LinkStatus.CONFLICT, MatchStrength.CANDIDATE, assertedApplicationId,
                        cap(candidates), List.of(reason("ASSERTION_CONTRADICTED",
                        "You added this message to one application, but the message itself identifies a different one.")),
                        List.of(), true);
            }
            if (assertedIsStrong) {
                List<Map<String, String>> rationale = new ArrayList<>(strongRationale(strong.get(assertedApplicationId),
                        requisitionByApp.get(assertedApplicationId)));
                rationale.add(reason("ADDED_BY_YOU", "You added this message to this application."));
                return new Association(LinkStatus.LINKED, MatchStrength.STRONG, assertedApplicationId,
                        List.of(new Candidate(assertedApplicationId, strong.get(assertedApplicationId))), rationale,
                        strongOnly(strong.get(assertedApplicationId)), othersStrong);
            }
            return new Association(LinkStatus.CONFIRMED, MatchStrength.CANDIDATE, assertedApplicationId,
                    List.of(new Candidate(assertedApplicationId, List.of("YOUR_CHOICE"))),
                    List.of(reason("ADDED_BY_YOU", "You added this message to this application; nothing in the "
                            + "message itself identifies the role, so it rests on your choice.")),
                    List.of(), false);
        }

        if (strong.size() == 1) {
            UUID id = strong.keySet().iterator().next();
            return new Association(LinkStatus.LINKED, MatchStrength.STRONG, id,
                    List.of(new Candidate(id, strong.get(id))), strongRationale(strong.get(id), requisitionByApp.get(id)),
                    strongOnly(strong.get(id)), false);
        }
        if (strong.size() > 1) {
            List<Candidate> candidates = new ArrayList<>();
            strong.forEach((id, signals) -> candidates.add(new Candidate(id, signals)));
            return new Association(LinkStatus.PENDING_REVIEW, MatchStrength.STRONG, null, cap(candidates),
                    List.of(reason("SEVERAL_APPLICATIONS_IDENTIFIED",
                            "The message identifies more than one of your applications, so Robin did not choose.")),
                    List.of(), true);
        }
        if (!weak.isEmpty()) {
            boolean anyCompany = weak.values().stream().anyMatch(s -> s.contains("COMPANY_NAME"));
            List<Candidate> candidates = weak.entrySet().stream()
                    .filter(e -> !anyCompany || e.getValue().contains("COMPANY_NAME"))
                    .sorted(Comparator.comparingInt((Map.Entry<UUID, List<String>> e) -> -e.getValue().size()))
                    .map(e -> new Candidate(e.getKey(), e.getValue()))
                    .toList();
            String explanation = candidates.size() > 1
                    ? "Only the company name or job title matched, and several of your applications fit. Similar roles are never guessed between."
                    : "Only the company name or job title matched. That is not enough to link the message without your confirmation.";
            return new Association(LinkStatus.PENDING_REVIEW, MatchStrength.WEAK, null, cap(candidates),
                    List.of(reason("WEAK_SIGNALS_ONLY", explanation)), List.of(), false);
        }
        return new Association(LinkStatus.UNMATCHED, MatchStrength.NONE, null, List.of(),
                List.of(reason("NO_IDENTIFIER", "Nothing in the message identifies one of your applications.")),
                List.of(), false);
    }

    private static boolean companyNamed(String company, String lowerText, String fromSquashed) {
        if (company == null || company.isBlank()) return false;
        String lowerCompany = TrackText.lower(TrackText.collapse(company));
        if (lowerCompany.length() >= 3 && containsWord(lowerText, lowerCompany)) return true;
        String key = JobIdentity.employerKey(company);
        return key != null && key.length() >= 3 && fromSquashed.contains(key);
    }

    private static boolean titleNamed(String title, String lowerText) {
        if (title == null) return false;
        String lowerTitle = TrackText.lower(TrackText.collapse(title));
        return lowerTitle.length() >= 6 && containsWord(lowerText, lowerTitle);
    }

    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (true) {
            int i = haystack.indexOf(needle, from);
            if (i < 0) return false;
            boolean leftOk = i == 0 || !Character.isLetterOrDigit(haystack.charAt(i - 1));
            int end = i + needle.length();
            boolean rightOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            from = i + 1;
        }
    }

    private static List<Map<String, String>> strongRationale(List<String> signals, String requisition) {
        List<Map<String, String>> out = new ArrayList<>();
        if (signals.contains("JOB_URL")) out.add(reason("JOB_URL", "The message links to this job's own posting."));
        if (signals.contains("POSTING_IDENTIFIER")) {
            out.add(reason("POSTING_IDENTIFIER", "The message contains this job posting's identifier."));
        }
        if (signals.contains("REQUISITION_NUMBER")) {
            out.add(reason("REQUISITION_NUMBER", "The message quotes this job's requisition number"
                    + (requisition == null ? "" : " (" + requisition.toUpperCase(java.util.Locale.ROOT) + ")")
                    + " together with the employer's name."));
        }
        return out;
    }

    private static List<String> strongOnly(List<String> signals) {
        return signals.stream().filter(s -> !s.equals("COMPANY_NAME") && !s.equals("JOB_TITLE")).toList();
    }

    private static List<Candidate> cap(List<Candidate> candidates) {
        return candidates.size() > MAX_CANDIDATES ? candidates.subList(0, MAX_CANDIDATES) : candidates;
    }

    static Map<String, String> reason(String code, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
