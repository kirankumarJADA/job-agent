package com.personal.jobagent.identity;

import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * External-account identities ({@code applicant_identities}) and the automation
 * sessions that drive them ({@code account_sessions}).
 *
 * <p>Every read and write here takes an {@code ownerProfileId} and uses it as a
 * predicate. That is the authorization boundary: a profile id is resolved from
 * the authenticated principal by the controller (never from a request body),
 * so a caller cannot reach another account's identity or session — an id they
 * do not own simply matches nothing.
 *
 * <p>The strictness is intentional. Before V021 a session had no owner column
 * at all, so the only options were to trust the caller or to deny everything.
 * Now ownership is recorded when the session is created, and an unattributable
 * legacy row fails the exact-match predicate — denied, which is the safe
 * direction.
 */
@Service
public class IdentityService {

    private static final String SESSION_COLUMNS =
            "id, application_id, applicant_identity_id, automation_plan_id, state, "
                    + "expected_domain, hard_stop_reason, created_at, updated_at";

    private final JdbcTemplate db;

    public IdentityService(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * Records (or refreshes) an external-account identity for the given profile.
     * {@code profileId} is the authenticated caller's own profile.
     */
    public UUID create(UUID profileId, String email, String domain, String credentialRef) {
        UUID id = UuidV7.generate();
        db.update("insert into applicant_identities(id,profile_id,applicant_email,credential_ref,domain) "
                        + "values(?,?,?,?,?) on conflict(profile_id,domain) do update set "
                        + "applicant_email=excluded.applicant_email, "
                        + "credential_ref=excluded.credential_ref, updated_at=now()",
                id, profileId, email, credentialRef, domain);
        return db.queryForObject(
                "select id from applicant_identities where profile_id=? and domain=?",
                UUID.class, profileId, domain);
    }

    /**
     * Starts an automation session for one of the caller's applications.
     *
     * <p>Ownership is checked <em>before</em> anything is written: the session
     * row and the back-reference on the application are only created when the
     * application row carries the caller's own {@code profile_id}. The
     * application id arrives from the request body, so without this check any
     * authenticated caller could overwrite {@code account_session_id} on
     * another candidate's application — an IDOR found in the isolation audit
     * and fixed here.
     *
     * @param ownerProfileId the authenticated caller's profile; recorded so the
     *                       session can be authorised on subsequent requests
     * @return empty when the application does not exist OR belongs to someone
     *         else — indistinguishable to the caller, per the API-wide 404 rule
     */
    public Optional<UUID> startSession(UUID applicationId, String expectedDomain, UUID ownerProfileId) {
        Integer owned = db.queryForObject(
                "select count(*) from applications where id=? and profile_id=?",
                Integer.class, applicationId, ownerProfileId);
        if (owned == null || owned == 0) {
            return Optional.empty();
        }
        UUID id = UuidV7.generate();
        db.update("insert into account_sessions(id,application_id,expected_domain,state,owner_profile_id) "
                + "values(?,?,?,'DISCOVER',?)", id, applicationId, expectedDomain, ownerProfileId);
        db.update("update applications set account_session_id=? where id=? and profile_id=?",
                id, applicationId, ownerProfileId);
        return Optional.of(id);
    }

    /** Ownership-agnostic transition. Used by internal progression, not by controllers. */
    public boolean transition(UUID id, String from, String to) {
        return db.update("update account_sessions set state=?,updated_at=now() where id=? and state=?",
                to, id, from) == 1;
    }

    /**
     * Reads a session, but only one owned by {@code ownerProfileId}.
     *
     * @return empty when the session does not exist OR belongs to someone else —
     *         the two are indistinguishable to the caller on purpose
     */
    public Optional<Map<String, Object>> session(UUID id, UUID ownerProfileId) {
        List<Map<String, Object>> rows = db.queryForList(
                "select " + SESSION_COLUMNS + " from account_sessions where id=? and owner_profile_id=?",
                id, ownerProfileId);
        return rows.stream().findFirst();
    }

    /**
     * Hard-stops one of the caller's own sessions.
     *
     * @return true when an owned, still-active session was stopped
     */
    public boolean hardStop(UUID id, String reason, UUID ownerProfileId) {
        return db.update("update account_sessions set state='HARD_STOP',hard_stop_reason=?,updated_at=now() "
                        + "where id=? and owner_profile_id=? and state not in ('COMPLETED','HARD_STOP')",
                reason, id, ownerProfileId) == 1;
    }
}
