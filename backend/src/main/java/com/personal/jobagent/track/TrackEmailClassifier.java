package com.personal.jobagent.track;

import com.personal.jobagent.track.TrackVocabulary.EmailCategory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, versioned classification of an employer message into a TRACK
 * category ({@value #VERSION}).
 *
 * <p>This is an <b>inference</b>, labelled as such everywhere it is shown. The
 * rules are phrase patterns with negation ("we have not received…") and
 * conditional ("if you are shortlisted we will invite you to interview…")
 * suppression, and a fixed precedence (rejection before offer before
 * interview…). The {@code confidence} value is a rule-strength score — strong
 * phrase 0.9, weaker cue 0.5–0.6, nothing matched 0.3 — not a probability and
 * never proof of anything: a classification on its own never changes an
 * application's status.
 *
 * <p>Message content is untrusted. The classifier only measures it with bounded
 * regular expressions over collapsed text; nothing in a message is executed,
 * followed or sent anywhere.
 */
public final class TrackEmailClassifier {

    public static final String VERSION = "track-email-rules-v1";

    public record Classification(EmailCategory category, double confidence, String ruleId,
                                 boolean explicitReceipt, String excerpt, List<String> signals) {
    }

    private record Rule(EmailCategory category, String id, Pattern pattern, boolean strong,
                        boolean explicitReceipt, boolean negatable, boolean conditional) {
    }

    private record Hit(Rule rule, int start, int end) {
    }

    private static final Pattern NEGATION = Pattern.compile("\\b(?:not|never|no longer|n't|without|unable to)\\b");
    private static final Pattern CONDITIONAL = Pattern.compile(
            "\\b(?:if|should|may|might|whether|in case|once|unless|could potentially|in the event)\\b");
    /** A condition stated right after the phrase ("…to schedule an interview if you are shortlisted"). */
    private static final Pattern TRAILING_CONDITION = Pattern.compile(
            "^[^.!?]{0,40}?\\b(?:if|should|once|in case|whether|provided that|subject to)\\b");

    private static final List<Rule> RULES = List.of(
            // ── sign-in verification (not tracking evidence) ───────────────
            rule(EmailCategory.VERIFICATION_CODE, "verification-code",
                    "\\b(?:verification|security|one[- ]?time|login|log-in|sign[- ]?in|access|authentication) (?:code|passcode|pin)\\b", true),
            rule(EmailCategory.VERIFICATION_CODE, "otp", "\\b(?:otp|passcode)\\b", true),
            rule(EmailCategory.VERIFICATION_CODE, "your-code", "\\byour code\\b", true),
            rule(EmailCategory.VERIFICATION_CODE, "use-code", "\\buse \\d{4,8}\\b", true),
            rule(EmailCategory.VERIFICATION_CODE, "code-is", "\\bcode(?: is|:) ?\\d{4,8}\\b", true),
            rule(EmailCategory.VERIFICATION_CODE, "verify-account",
                    "\\bverify your (?:email|e-mail|account|identity|address)\\b", true),

            // ── rejection ──────────────────────────────────────────────────
            conditionalRule(EmailCategory.REJECTION, "not-progressing",
                    "\\b(?:will|would|shall|are|is) not be (?:moving|progressing|proceeding|taking|inviting|advancing|pursuing)\\b"),
            conditionalRule(EmailCategory.REJECTION, "not-moving-forward",
                    "\\bnot (?:to )?(?:move|moving|progress|progressing|proceed|proceeding|advance|advancing) (?:forward |further )?with your (?:application|candidacy)\\b"),
            conditionalRule(EmailCategory.REJECTION, "other-candidates",
                    "\\b(?:decided|chosen|opted|decision) (?:not )?to (?:move|proceed|go|progress|pursue|continue) (?:forward |ahead )?with (?:other|another|a different|different)\\b"),
            conditionalRule(EmailCategory.REJECTION, "regret-to-inform", "\\bregret to (?:inform|let you know|advise|tell)\\b"),
            conditionalRule(EmailCategory.REJECTION, "application-unsuccessful",
                    "\\byour application (?:was|has been|is) (?:unsuccessful|not successful|declined|rejected)\\b"),
            conditionalRule(EmailCategory.REJECTION, "position-filled", "\\b(?:position|role|vacancy) has (?:now )?been filled\\b"),
            conditionalRule(EmailCategory.REJECTION, "not-selected",
                    "\\b(?:not|haven't|have not|were not|was not) (?:been )?(?:selected|shortlisted)\\b"),
            conditionalRule(EmailCategory.REJECTION, "unfortunately-not",
                    "\\bunfortunately\\b[^.!?]{0,120}?\\b(?:not|unable|other candidates|unsuccessful)\\b"),
            weakRule(EmailCategory.REJECTION, "unsuccessful", "\\bunsuccessful\\b", false, true),

            // ── offer ──────────────────────────────────────────────────────
            positiveRule(EmailCategory.OFFER, "pleased-to-offer",
                    "\\b(?:pleased|delighted|happy|excited|thrilled|glad) to (?:extend|make|offer|send)(?: you)?(?: an?| the| our)?(?: formal| verbal| written| conditional| official)? offer\\b"),
            positiveRule(EmailCategory.OFFER, "offer-of-employment", "\\boffer of employment\\b"),
            positiveRule(EmailCategory.OFFER, "formal-offer",
                    "\\b(?:formal|verbal|written|conditional|official|job|employment) offer\\b"),
            positiveRule(EmailCategory.OFFER, "offer-letter", "\\boffer letter\\b"),
            positiveRule(EmailCategory.OFFER, "offer-you-the-role",
                    "\\b(?:we(?:'d| would) like to|we want to|we are able to|we can) offer you (?:the|this|a) (?:position|role|job)\\b"),

            // ── interview ──────────────────────────────────────────────────
            positiveRule(EmailCategory.INTERVIEW_INVITATION, "invite-to-interview",
                    "\\binvit(?:e|es|ed|ing|ation) (?:you )?(?:to|for) (?:an? |the |your |our )?(?:[a-z-]+ ){0,2}?(?:interview|call|conversation|chat|meeting)\\b"),
            positiveRule(EmailCategory.INTERVIEW_INVITATION, "interview-invitation", "\\binterview invitation\\b"),
            positiveRule(EmailCategory.INTERVIEW_INVITATION, "schedule-interview",
                    "\\b(?:schedule|arrange|book|set up|organise|organize) (?:an? |the |your |our )?(?:[a-z-]+ ){0,2}?(?:interview|call)\\b"),
            positiveRule(EmailCategory.INTERVIEW_INVITATION, "like-to-speak",
                    "\\b(?:like|love) to (?:speak|talk|chat|meet) (?:with )?you\\b"),
            positiveRule(EmailCategory.INTERVIEW_INVITATION, "availability-for-interview",
                    "\\bavailab(?:le|ility) (?:for|to have) (?:an? |the )?(?:[a-z-]+ ){0,1}?(?:interview|call|chat|conversation)\\b"),
            weakRule(EmailCategory.INTERVIEW_INVITATION, "named-interview",
                    "\\b(?:phone|video|technical|onsite|on-site|final|first|second|panel|virtual) (?:round |stage )?interview\\b", true, true),

            // ── assessment ─────────────────────────────────────────────────
            positiveRule(EmailCategory.ASSESSMENT_INVITATION, "named-assessment",
                    "\\b(?:online|technical|coding|skills?|written|take-home|take home|psychometric|aptitude|cognitive|numerical|verbal reasoning) (?:assessment|test|challenge|exercise|assignment)\\b"),
            positiveRule(EmailCategory.ASSESSMENT_INVITATION, "complete-assessment",
                    "\\bcomplete (?:the |an? |our |this )?(?:[a-z-]+ )?(?:assessment|test|challenge|assignment)\\b"),
            weakRule(EmailCategory.ASSESSMENT_INVITATION, "assessment-platform",
                    "\\b(?:hackerrank|codility|codesignal|testgorilla|coderpad|hirevue|pymetrics)\\b", true, true),

            // ── request for more information ───────────────────────────────
            positiveRule(EmailCategory.INFORMATION_REQUEST, "could-you-provide",
                    "\\b(?:could|can|would) you (?:please )?(?:provide|send|share|confirm|complete|upload|fill in|let us know)\\b"),
            positiveRule(EmailCategory.INFORMATION_REQUEST, "need-more-information",
                    "\\b(?:we|i) (?:need|require) (?:some )?(?:additional|more|further) (?:information|details|documents?|documentation)\\b"),
            positiveRule(EmailCategory.INFORMATION_REQUEST, "please-provide",
                    "\\bplease (?:provide|send us|send me|upload|complete the|fill in|confirm your)\\b"),
            weakRule(EmailCategory.INFORMATION_REQUEST, "right-to-work", "\\bright to work\\b", true, true),

            // ── acknowledgement ────────────────────────────────────────────
            receiptRule("received-your-application",
                    "\\b(?:we(?:'ve| have)|we have successfully|we've successfully) received your application\\b"),
            receiptRule("application-has-been-received",
                    "\\byour application (?:for [^.!?]{1,120}? )?(?:has been|was|is) (?:successfully )?(?:received|submitted)\\b"),
            receiptRule("application-received",
                    "\\bapplication (?:has been )?(?:successfully )?(?:received|submitted)\\b"),
            receiptRule("confirm-receipt",
                    "\\b(?:confirm(?:ing)?|confirmation of|acknowledg(?:e|ing)) (?:that )?(?:we(?:'ve| have) )?(?:received|receipt of) your application\\b"),
            receiptRule("this-confirms-received",
                    "\\bthis (?:email|message|note) (?:is to )?confirms? (?:that )?(?:we(?:'ve| have) )?received\\b"),
            new Rule(EmailCategory.APPLICATION_ACKNOWLEDGEMENT, "thanks-for-applying",
                    Pattern.compile("\\bthank(?:s| you) for (?:applying|submitting your application|your application|your recent application)\\b"),
                    true, false, true, false),

            // ── recruiter follow-up ────────────────────────────────────────
            weakRule(EmailCategory.RECRUITER_FOLLOW_UP, "follow-up",
                    "\\b(?:following up|follow up on|checking in|touch base|next steps?|an update on your application|regarding your application|about your application|your candidacy)\\b",
                    false, false),
            weakRule(EmailCategory.RECRUITER_FOLLOW_UP, "recruiting-team",
                    "\\brecruit(?:er|ing|ment) (?:team|partner|consultant|manager|coordinator)\\b", false, false)
    );

    public Classification classify(String subject, String body) {
        String text = TrackText.collapse(TrackText.scanText(subject, body));
        String lower = lowerAligned(text);

        Map<EmailCategory, Hit> strong = new EnumMap<>(EmailCategory.class);
        Map<EmailCategory, Hit> weak = new EnumMap<>(EmailCategory.class);
        Hit explicitReceipt = null;
        List<String> signals = new ArrayList<>();
        for (Rule rule : RULES) {
            Hit hit = firstEffectiveHit(rule, lower);
            if (hit == null) continue;
            signals.add(rule.category() + ":" + rule.id());
            (rule.strong() ? strong : weak).putIfAbsent(rule.category(), hit);
            if (rule.explicitReceipt() && explicitReceipt == null) explicitReceipt = hit;
        }

        boolean hiringSignal = strong.containsKey(EmailCategory.REJECTION) || weak.containsKey(EmailCategory.REJECTION)
                || strong.containsKey(EmailCategory.OFFER) || strong.containsKey(EmailCategory.INTERVIEW_INVITATION)
                || strong.containsKey(EmailCategory.ASSESSMENT_INVITATION)
                || strong.containsKey(EmailCategory.INFORMATION_REQUEST);
        if (strong.containsKey(EmailCategory.VERIFICATION_CODE) && explicitReceipt == null && !hiringSignal) {
            // No excerpt: a sign-in message's text is the secret itself.
            return new Classification(EmailCategory.VERIFICATION_CODE, 0.9,
                    strong.get(EmailCategory.VERIFICATION_CODE).rule().id(), false, null, signals);
        }
        boolean rejection = strong.containsKey(EmailCategory.REJECTION) || weak.containsKey(EmailCategory.REJECTION);
        if (rejection && strong.containsKey(EmailCategory.OFFER)) {
            return new Classification(EmailCategory.UNRELATED_OR_AMBIGUOUS, 0.3, "conflicting-outcomes",
                    explicitReceipt != null, null, signals);
        }
        Hit decided;
        double confidence;
        if ((decided = strong.get(EmailCategory.REJECTION)) != null) confidence = 0.9;
        else if ((decided = weak.get(EmailCategory.REJECTION)) != null) confidence = 0.6;
        else if ((decided = strong.get(EmailCategory.OFFER)) != null) confidence = 0.9;
        else if ((decided = strong.get(EmailCategory.INTERVIEW_INVITATION)) != null) confidence = 0.9;
        else if ((decided = strong.get(EmailCategory.ASSESSMENT_INVITATION)) != null) confidence = 0.9;
        else if ((decided = strong.get(EmailCategory.INFORMATION_REQUEST)) != null) confidence = 0.8;
        else if ((decided = strong.get(EmailCategory.APPLICATION_ACKNOWLEDGEMENT)) != null) {
            confidence = explicitReceipt != null ? 0.9 : 0.75;
            if (explicitReceipt != null) decided = explicitReceipt;
        }
        else if ((decided = weak.get(EmailCategory.INTERVIEW_INVITATION)) != null) confidence = 0.6;
        else if ((decided = weak.get(EmailCategory.ASSESSMENT_INVITATION)) != null) confidence = 0.6;
        else if ((decided = weak.get(EmailCategory.INFORMATION_REQUEST)) != null) confidence = 0.5;
        else if ((decided = weak.get(EmailCategory.RECRUITER_FOLLOW_UP)) != null) confidence = 0.6;
        else {
            return new Classification(EmailCategory.UNRELATED_OR_AMBIGUOUS, 0.3, "no-rule", false, null, signals);
        }
        return new Classification(decided.rule().category(), confidence, decided.rule().id(),
                explicitReceipt != null, TrackText.excerpt(text, decided.start(), decided.end()), signals);
    }

    /** The first match of a rule that is not negated or conditional within its own sentence. */
    private static Hit firstEffectiveHit(Rule rule, String lower) {
        Matcher m = rule.pattern().matcher(lower);
        while (m.find()) {
            String before = sentencePrefix(lower, m.start());
            if (rule.negatable() && tail(before, 30, NEGATION)) continue;
            if (rule.conditional() && tail(before, 70, CONDITIONAL)) continue;
            if (rule.conditional() && TRAILING_CONDITION.matcher(
                    lower.substring(m.end(), Math.min(lower.length(), m.end() + 60))).find()) continue;
            return new Hit(rule, m.start(), m.end());
        }
        return null;
    }

    private static String sentencePrefix(String lower, int start) {
        int from = Math.max(0, start - 120);
        String window = lower.substring(from, start);
        int cut = Math.max(window.lastIndexOf('.'), Math.max(window.lastIndexOf('!'), window.lastIndexOf('?')));
        return cut >= 0 ? window.substring(cut + 1) : window;
    }

    private static boolean tail(String before, int chars, Pattern pattern) {
        String t = before.length() > chars ? before.substring(before.length() - chars) : before;
        return pattern.matcher(t).find();
    }

    /** Length-preserving lower-casing, so match offsets index the original text for excerpts. */
    private static String lowerAligned(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) chars[i] = Character.toLowerCase(chars[i]);
        return new String(chars);
    }

    private static Rule rule(EmailCategory category, String id, String regex, boolean strong) {
        return new Rule(category, id, Pattern.compile(regex), strong, false, false, false);
    }

    private static Rule positiveRule(EmailCategory category, String id, String regex) {
        return new Rule(category, id, Pattern.compile(regex), true, false, true, true);
    }

    private static Rule conditionalRule(EmailCategory category, String id, String regex) {
        return new Rule(category, id, Pattern.compile(regex), true, false, false, true);
    }

    private static Rule weakRule(EmailCategory category, String id, String regex, boolean negatable, boolean conditional) {
        return new Rule(category, id, Pattern.compile(regex), false, false, negatable, conditional);
    }

    private static Rule receiptRule(String id, String regex) {
        return new Rule(EmailCategory.APPLICATION_ACKNOWLEDGEMENT, id, Pattern.compile(regex), true, true, true, false);
    }
}
