package com.personal.jobagent.track;

import com.personal.jobagent.track.TrackVocabulary.Channel;
import com.personal.jobagent.track.TrackVocabulary.EvidenceType;
import com.personal.jobagent.track.TrackVocabulary.Verification;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The documented verification policy for submission/acknowledgement evidence
 * ({@value #VERSION}). Pure and deterministic: the same evidence always gets the
 * same outcome, and every outcome carries the reasons that produced it.
 *
 * <p>Outcomes, evaluated in this order:
 * <ol>
 *   <li><b>REJECTED</b> — the evidence's own identifiers point to a different one
 *       of the candidate's applications and not to this one; or it is dated more
 *       than a day in the future.</li>
 *   <li><b>UNKNOWN</b> — nothing in the evidence identifies this application (no
 *       job link, posting identifier or requisition number), or it identifies
 *       several applications. The association then rests on the candidate's
 *       choice or on weak signals, which cannot establish a receipt.</li>
 *   <li><b>UNVERIFIED</b> — the evidence identifies this application but did not
 *       arrive through an authenticated channel, the sender's authentication
 *       did not pass for the sender's own domain, it was not delivered to the
 *       connected mailbox, or its wording does not explicitly acknowledge
 *       receipt. A candidate statement is always UNVERIFIED.</li>
 *   <li><b>VERIFIED</b> — all of: retrieved by an authorised mailbox connector;
 *       DKIM pass for a domain aligned with the From address and no SPF/DMARC
 *       failure, as reported by the mail provider; delivered to the connected
 *       mailbox; strong identity evidence for exactly this application; an
 *       explicit receipt phrase ("we have received your application"); and
 *       (v2) a provider-assigned arrival time that does not predate the
 *       submission beyond the clock-skew allowance ({@link ReceiptTemporalPolicy}).</li>
 * </ol>
 *
 * <p>Preparing, approving, queueing or attempting an application is never
 * evidence: none of those reaches this policy at all. A confidence score is not
 * an input. The schema enforces the two most important rules independently: a
 * CANDIDATE_STATEMENT and any non-connector channel can never be VERIFIED.
 */
public final class ReceiptVerificationPolicy {

    public static final String VERSION = "track-receipt-policy-v2";
    private static final Duration FUTURE_TOLERANCE = Duration.ofDays(1);

    /** Sender authentication as reported by a mail provider (never by a request body). */
    public record SenderAuthentication(String dkim, String dkimDomain, String spf, String dmarc) {

        public static SenderAuthentication none() {
            return new SenderAuthentication(null, null, null, null);
        }

        boolean passesFor(String fromDomain) {
            if (fromDomain == null || dkimDomain == null) return false;
            if (!"pass".equalsIgnoreCase(dkim)) return false;
            if ("fail".equalsIgnoreCase(spf) || "fail".equalsIgnoreCase(dmarc)) return false;
            String signer = dkimDomain.toLowerCase(Locale.ROOT);
            String from = fromDomain.toLowerCase(Locale.ROOT);
            return from.equals(signer) || from.endsWith("." + signer);
        }
    }

    /**
     * @param timing the server-side temporal assessment ({@link ReceiptTemporalPolicy}); when null, the
     *     evidence time alone is checked: an unknown time can never be verified
     */
    public record Evidence(EvidenceType type, Channel channel, boolean identifiesThisApplication,
                           boolean identifiesOtherApplication, boolean explicitReceiptPhrase,
                           String fromDomain, SenderAuthentication authentication,
                           boolean deliveredToConnectedMailbox, Instant evidenceTime, Instant now,
                           EmployerDomainPolicy.DomainBinding domainBinding,
                           ReceiptTemporalPolicy.Assessment timing) {
        public Evidence(EvidenceType type, Channel channel, boolean identifiesThisApplication,
                        boolean identifiesOtherApplication, boolean explicitReceiptPhrase,
                        String fromDomain, SenderAuthentication authentication,
                        boolean deliveredToConnectedMailbox, Instant evidenceTime, Instant now,
                        EmployerDomainPolicy.DomainBinding domainBinding) {
            this(type, channel, identifiesThisApplication, identifiesOtherApplication, explicitReceiptPhrase,
                    fromDomain, authentication, deliveredToConnectedMailbox, evidenceTime, now, domainBinding, null);
        }

        /** Convenience constructor without an explicit employer-domain binding (fails closed for external emails). */
        public Evidence(EvidenceType type, Channel channel, boolean identifiesThisApplication,
                        boolean identifiesOtherApplication, boolean explicitReceiptPhrase,
                        String fromDomain, SenderAuthentication authentication,
                        boolean deliveredToConnectedMailbox, Instant evidenceTime, Instant now) {
            this(type, channel, identifiesThisApplication, identifiesOtherApplication, explicitReceiptPhrase,
                    fromDomain, authentication, deliveredToConnectedMailbox, evidenceTime, now, null, null);
        }
    }

    public record Outcome(Verification status, List<Map<String, String>> reasons) {
    }

    public Outcome evaluate(Evidence e) {
        List<Map<String, String>> reasons = new ArrayList<>();
        Instant now = e.now() == null ? Instant.now() : e.now();

        if (e.type() == EvidenceType.CANDIDATE_STATEMENT) {
            reasons.add(reason("CANDIDATE_STATEMENT",
                    "This is your own statement. Robin records it as reported by you; it cannot be verified."));
            return new Outcome(Verification.UNVERIFIED, reasons);
        }

        if ((e.evidenceTime() != null && e.evidenceTime().isAfter(now.plus(FUTURE_TOLERANCE)))
                || (e.timing() != null && e.timing().status() == ReceiptTemporalPolicy.Status.FUTURE_DATED)) {
            reasons.add(reason("FUTURE_DATED", "The evidence is dated in the future, which is contradictory."));
            return new Outcome(Verification.REJECTED, reasons);
        }
        if (e.identifiesOtherApplication() && !e.identifiesThisApplication()) {
            reasons.add(reason("IDENTIFIES_A_DIFFERENT_APPLICATION",
                    "The message's own job link, posting identifier or requisition number belongs to a different application."));
            return new Outcome(Verification.REJECTED, reasons);
        }
        if (!e.identifiesThisApplication()) {
            reasons.add(reason("NO_IDENTIFIER_FOR_THIS_APPLICATION",
                    "Nothing in the message identifies this specific application (no job link, posting identifier "
                            + "or requisition number), so it cannot show that this application was received."));
            return new Outcome(Verification.UNKNOWN, reasons);
        }
        if (e.identifiesOtherApplication()) {
            reasons.add(reason("IDENTIFIES_SEVERAL_APPLICATIONS",
                    "The message identifies more than one of your applications."));
            return new Outcome(Verification.UNKNOWN, reasons);
        }
        reasons.add(reason("IDENTIFIES_THIS_APPLICATION",
                "The message identifies this application by its job link, posting identifier or requisition number."));

        boolean verified = true;
        if (e.channel() != Channel.MAILBOX_CONNECTOR) {
            verified = false;
            reasons.add(reason("CHANNEL_NOT_AUTHENTICATED",
                    "The message was added by you, not retrieved from your mailbox by a connected account, so its "
                            + "sender cannot be authenticated."));
        } else {
            SenderAuthentication auth = e.authentication() == null ? SenderAuthentication.none() : e.authentication();
            if (!auth.passesFor(e.fromDomain())) {
                verified = false;
                reasons.add(reason("SENDER_NOT_AUTHENTICATED",
                        "The mail provider did not report a passing signature for the sender's own domain."));
            } else {
                reasons.add(reason("SENDER_AUTHENTICATED", "The mail provider reported a passing signature for the sender's domain."));
            }
            EmployerDomainPolicy.DomainBinding binding = e.domainBinding() == null
                    ? EmployerDomainPolicy.DomainBinding.unknown()
                    : e.domainBinding();
            if (!binding.isBound()) {
                verified = false;
                if (binding.status() == EmployerDomainPolicy.BindingStatus.UNBOUND_MISMATCH) {
                    reasons.add(reason("SENDER_DOMAIN_NOT_BOUND_TO_EMPLOYER",
                            "The authenticated sender domain is not bound to this employer's corporate domain or a vetted ATS notification domain."));
                } else {
                    reasons.add(reason("EMPLOYER_TARGET_DOMAIN_UNKNOWN",
                            "The employer's verified target domain for this application is unknown or ambiguous."));
                }
            } else {
                reasons.add(reason("EMPLOYER_DOMAIN_BOUND",
                        "The authenticated sender domain matches the employer's corporate domain or a vetted ATS notification domain."));
            }
            if (!e.deliveredToConnectedMailbox()) {
                verified = false;
                reasons.add(reason("NOT_DELIVERED_TO_YOUR_MAILBOX", "The message was not delivered to your connected mailbox."));
            }
            ReceiptTemporalPolicy.Assessment timing = e.timing();
            if (e.evidenceTime() == null || (timing != null && timing.status() == ReceiptTemporalPolicy.Status.EVIDENCE_TIME_UNKNOWN)) {
                verified = false;
                reasons.add(reason("EVIDENCE_TIME_UNKNOWN",
                        "The mail provider did not record when the message arrived, so it cannot be checked against the submission."));
            } else if (timing != null && timing.status() == ReceiptTemporalPolicy.Status.BEFORE_SUBMISSION) {
                verified = false;
                reasons.add(reason("RECEIPT_PREDATES_SUBMISSION", timing.message()));
            } else if (timing != null) {
                reasons.add(reason(timing.code(), timing.message()));
            }
        }
        if (!e.explicitReceiptPhrase()) {
            verified = false;
            reasons.add(reason("NO_EXPLICIT_ACKNOWLEDGEMENT",
                    "The wording does not explicitly say your application was received."));
        } else {
            reasons.add(reason("EXPLICIT_ACKNOWLEDGEMENT", "The message explicitly says your application was received."));
        }
        return new Outcome(verified ? Verification.VERIFIED : Verification.UNVERIFIED, reasons);
    }

    static Map<String, String> reason(String code, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
