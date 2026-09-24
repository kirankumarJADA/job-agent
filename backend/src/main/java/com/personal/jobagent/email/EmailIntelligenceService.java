package com.personal.jobagent.email;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.identity.CredentialVault;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.security.OwnerContext;
import com.personal.jobagent.application.ApplicationStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Correlates inbox messages with the caller's applications and classifies them.
 *
 * <p><b>Ownership is a required parameter, not an inference.</b> Every entry
 * point takes the owning profile id and every statement filters on it. The
 * version of this class that had no owner had three distinct cross-user
 * problems, all of which are now closed:
 *
 * <ul>
 *   <li><b>Message de-duplication was global</b> ({@code emails.message_id} was
 *       unique database-wide). Ingesting a message id that another account had
 *       already ingested returned <em>their</em> email row — classification and
 *       all — instead of storing the caller's own copy. V022 makes the
 *       uniqueness (profile_id, message_id); this lookup filters by owner
 *       first.</li>
 *   <li><b>Application correlation scanned all applications.</b> A supplied
 *       subject/body matching a company name could attach the message to
 *       another candidate's application, after which a status transition would
 *       fire against it. The join is now restricted to the caller's own
 *       applications.</li>
 *   <li><b>OTP extraction and resolution were unowned.</b> Resolving a
 *       verification code is a high-value action (it is literally the employer
 *       portal's one-time code), so both extraction and resolution now require
 *       that the source email — and the automation session the code is for —
 *       belong to the caller.</li>
 * </ul>
 */
@Service
public class EmailIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(EmailIntelligenceService.class);

    public record IngestResult(UUID emailId, String classification, UUID applicationId, boolean replayed) {
    }

    private static final Pattern OTP = Pattern.compile("\\b\\d{4,8}\\b");

    private final JdbcTemplate db;
    private final CredentialVault vault;
    private final NotificationService notifications;
    private final ApplicationStatusService statusService;
    private final OwnerContext ownerContext;

    public EmailIntelligenceService(JdbcTemplate db, CredentialVault vault, NotificationService notifications,
                                    ApplicationStatusService statusService, OwnerContext ownerContext) {
        this.db = db;
        this.vault = vault;
        this.notifications = notifications;
        this.statusService = statusService;
        this.ownerContext = ownerContext;
    }

    /**
     * Stores an ingested message against the given owner and classifies it.
     *
     * @param ownerProfileId the profile the message belongs to. Required: an
     *                       email that cannot be attributed is not stored,
     *                       because storing it unowned would either hide it
     *                       from its owner or expose it to everyone.
     */
    public IngestResult ingest(UUID ownerProfileId, String messageId, String from, String to,
                               String subject, String body, Instant received) {
        Objects.requireNonNull(ownerProfileId, "ownerProfileId is required to ingest an email");
        Instant receivedAt = received == null ? Instant.now() : received;

        if (messageId != null) {
            // Owner-scoped replay detection: another account having ingested this
            // message id must not surface their row here.
            List<UUID> ids = db.query("select id from emails where message_id=? and profile_id=?",
                    (rs, n) -> (UUID) rs.getObject(1), messageId, ownerProfileId);
            if (!ids.isEmpty()) {
                Map<String, Object> existing = db.queryForMap(
                        "select id,classification,application_id from emails where id=?", ids.get(0));
                return new IngestResult((UUID) existing.get("id"), (String) existing.get("classification"),
                        (UUID) existing.get("application_id"), true);
            }
        }

        UUID applicationId = findApplication(subject + " " + body, ownerProfileId);
        String classification = classify(subject + " " + body);
        UUID emailId = UuidV7.generate();

        db.update("""
                insert into emails(id,message_id,from_address,to_address,subject,body_text,received_at,
                                   application_id,classification,classification_confidence,classification_reason,profile_id)
                values(?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                emailId, messageId, from, to, subject, body, Timestamp.from(receivedAt),
                applicationId, classification, 0.95, "deterministic local classifier", ownerProfileId);

        if (applicationId != null) {
            notifications.emit(new NotificationService.NotificationCommand(
                    notificationEvent(classification), "APPLICATION", applicationId,
                    Map.of("email_id", emailId.toString(), "classification", classification,
                            "profile_id", ownerProfileId.toString()),
                    UuidV7.generate(), null));
            applyStatusFromClassification(ownerProfileId, applicationId, classification, emailId);
        }
        return new IngestResult(emailId, classification, applicationId, false);
    }

    private void applyStatusFromClassification(UUID ownerProfileId, UUID applicationId,
                                               String classification, UUID emailId) {
        String target = switch (classification) {
            case "CONFIRMATION" -> "CONFIRMATION_RECEIVED";
            case "INTERVIEW_INVITE" -> "INTERVIEW";
            case "ASSESSMENT_INVITE" -> "ASSESSMENT";
            case "REJECTION" -> "REJECTED";
            case "OFFER" -> "OFFER";
            case "RECRUITER_OUTREACH" -> "RECRUITER_CONTACT";
            default -> null;
        };
        if (target == null) {
            return;
        }
        // Audit under the owning account's own identity, so the transition
        // appears in their audit trail rather than under a service label that
        // could not be attributed to anyone.
        String actor = ownerContext.emailForProfile(ownerProfileId).orElse("EMAIL_INTELLIGENCE");
        try {
            statusService.apply(ownerProfileId, applicationId, target, "email:" + emailId, actor,
                    Map.of("email_id", emailId.toString(), "classification", classification));
        } catch (IllegalStateException | java.util.NoSuchElementException ignored) {
            // Duplicate or illegal transition — nothing to do.
        }
    }

    /**
     * Extracts a verification code from one of the caller's own emails, for an
     * automation session that is also the caller's.
     */
    public Optional<Map<String, Object>> extractVerification(UUID ownerProfileId, UUID emailId,
                                                             UUID sessionId, String expectedDomain) {
        if (ownerProfileId == null || emailId == null || sessionId == null) {
            return Optional.empty();
        }

        List<Map<String, Object>> owned = db.queryForList("""
                select e.from_address, e.subject, e.body_text, e.received_at
                from emails e
                where e.id = ? and e.profile_id = ?
                """, emailId, ownerProfileId);
        if (owned.isEmpty()) {
            // Not the caller's email: indistinguishable from a missing one.
            return Optional.empty();
        }

        boolean sessionOwned = ownerContext.ownerOfAccountSession(sessionId)
                .filter(ownerProfileId::equals)
                .isPresent();
        if (!sessionOwned) {
            log.debug("Refusing verification extraction: automation session is not owned by the caller");
            return Optional.empty();
        }

        Map<String, Object> email = owned.get(0);
        String sender = String.valueOf(email.get("from_address")).toLowerCase(Locale.ROOT);
        if (expectedDomain != null && !sender.endsWith("@" + expectedDomain.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        Instant receivedAt = ((Timestamp) email.get("received_at")).toInstant();
        if (receivedAt.isBefore(Instant.now().minus(Duration.ofMinutes(30)))) {
            return Optional.empty();
        }
        Matcher matcher = OTP.matcher(email.get("subject") + " " + email.get("body_text"));
        if (!matcher.find()) {
            return Optional.empty();
        }

        String valueRef = vault.store(matcher.group(), Duration.ofMinutes(10));
        UUID id = UuidV7.generate();
        try {
            db.update("""
                    insert into verification_extractions(id,source_email_id,account_session_id,verification_type,value_ref,confidence,expires_at)
                    values(?,?,?,?,?,?,?)
                    """, id, emailId, sessionId, "OTP", valueRef, 0.95,
                    Timestamp.from(Instant.now().plus(Duration.ofMinutes(10))));
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // Already extracted for this email.
        }
        return Optional.of(Map.of("extractionId", id, "verificationType", "OTP", "valueAvailable", true));
    }

    /**
     * Resolves (and consumes) an extracted code. Gated on the code having come
     * from one of the caller's own emails — otherwise a caller who guessed an
     * extraction id would be handed another account's live OTP.
     */
    public String resolve(UUID ownerProfileId, UUID id) {
        if (ownerProfileId == null || id == null) {
            throw new IllegalStateException("verification unavailable");
        }
        List<Map<String, Object>> rows = db.queryForList("""
                select v.value_ref, v.consumed_at, v.expires_at
                from verification_extractions v
                join emails e on e.id = v.source_email_id
                where v.id = ? and e.profile_id = ?
                """, id, ownerProfileId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("verification unavailable");
        }
        Map<String, Object> row = rows.get(0);
        if (row.get("consumed_at") != null
                || ((Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())) {
            throw new IllegalStateException("verification unavailable");
        }
        if (db.update("update verification_extractions set consumed_at=now() where id=? and consumed_at is null", id) != 1) {
            throw new IllegalStateException("verification already consumed");
        }
        return vault.resolveOnce((String) row.get("value_ref"));
    }

    /**
     * Correlates a message with one of the owner's applications by application
     * id or company name. Restricted to that owner's applications: matching
     * across all accounts was how a stranger's message could be attached to
     * another candidate's application.
     */
    private UUID findApplication(String text, UUID ownerProfileId) {
        List<Map<String, Object>> rows = db.queryForList("""
                select a.id from applications a
                join jobs j on j.id = a.job_id
                where a.profile_id = ?
                  and (lower(?) like lower('%' || a.id::text || '%')
                       or lower(?) like lower('%' || coalesce(j.company_name_raw,'') || '%'))
                """, ownerProfileId, text, text);
        return rows.size() == 1 ? (UUID) rows.get(0).get("id") : null;
    }

    private String notificationEvent(String classification) {
        return switch (classification) {
            case "INTERVIEW_INVITE" -> NotificationEvents.INTERVIEW_INVITATION;
            case "ASSESSMENT_INVITE" -> NotificationEvents.ASSESSMENT_RECEIVED;
            case "REJECTION" -> NotificationEvents.REJECTION_RECEIVED;
            case "OFFER" -> NotificationEvents.OFFER_RECEIVED;
            case "CONFIRMATION" -> NotificationEvents.CONFIRMATION_EMAIL_RECEIVED;
            default -> NotificationEvents.RECRUITER_REPLY;
        };
    }

    private String classify(String text) {
        String s = text.toLowerCase(Locale.ROOT);
        if (s.contains("interview")) return "INTERVIEW_INVITE";
        if (s.contains("assessment")) return "ASSESSMENT_INVITE";
        if (s.contains("reject")) return "REJECTION";
        if (s.contains("offer")) return "OFFER";
        if (s.contains("confirm")) return "CONFIRMATION";
        if (s.contains("recruiter") || s.contains("application")) return "RECRUITER_OUTREACH";
        return "OTHER";
    }
}
