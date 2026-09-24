package com.personal.jobagent.security;

import com.personal.jobagent.profile.ProfileRecord;
import com.personal.jobagent.profile.ProfileRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Ownership resolution, in one place.
 *
 * <p>Two questions are asked all over the codebase and both must have exactly
 * one answer:
 *
 * <ol>
 *   <li><b>Who is calling?</b> — {@link #profileIdOrNull()} /
 *       {@link #requireProfileId()}. The owner of every row a request creates
 *       or reads is the caller's own profile, resolved from the authenticated
 *       principal. It is never read from a request body, which is the mistake
 *       that made the previous {@code IdentityController} an IDOR (it took
 *       {@code profileId} from the JSON body and passed it to the service).</li>
 *   <li><b>Who owns this existing row?</b> — {@link #ownerOfApplication} and
 *       friends. Background paths have no {@code SecurityContext} (the outbox
 *       dispatcher, scheduled discovery), so they must derive ownership from
 *       the row's own foreign-key chain instead. That derivation is the only
 *       reason these queries live here rather than in each caller.</li>
 * </ol>
 *
 * <p>Ownership root is {@code profiles.id}: V001 already makes profiles the one
 * row per user ({@code unique(user_id)}) that every user-owned table hangs off.
 * Not every account has a profile yet — a brand-new Firebase account is given
 * one at provisioning time — so the {@code OrNull} methods exist and callers
 * must decide what "no profile" means for them (404 for a user-facing read,
 * skip for a background notification). What they must never do is treat it as
 * "match everything".
 */
@Component
public class OwnerContext {

    /** Principal name the worker bearer-token filter installs (see WorkerEventTokenFilter). */
    private static final String WORKER_PRINCIPAL = "worker";

    private final ProfileRepository profileRepository;
    private final JdbcTemplate jdbcTemplate;

    public OwnerContext(ProfileRepository profileRepository, JdbcTemplate jdbcTemplate) {
        this.profileRepository = profileRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── who is calling ────────────────────────────────────────────────

    /** The authenticated account id, or null when the request is anonymous. */
    public UUID userIdOrNull() {
        AppUserDetails principal = principalOrNull();
        return principal == null ? null : principal.getUserId();
    }

    /**
     * The calling account's profile id, or null when unauthenticated or when the
     * account has no profile yet.
     */
    public UUID profileIdOrNull() {
        UUID userId = userIdOrNull();
        if (userId == null) {
            return null;
        }
        return profileRepository.findByUserId(userId).map(ProfileRecord::id).orElse(null);
    }

    /** As {@link #profileIdOrNull()} but refuses instead of returning null. */
    public UUID requireProfileId() {
        UUID profileId = profileIdOrNull();
        if (profileId == null) {
            throw new IllegalStateException("No profile exists for the current user");
        }
        return profileId;
    }

    /** The authenticated account's email, or the given fallback. */
    public String actorOr(String fallback) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return fallback;
        }
        return authentication.getName();
    }

    /**
     * True when this request was authenticated by the worker bearer token rather
     * than a user session. Worker endpoints are machine-to-machine and are
     * deliberately not profile-scoped — but a <em>user</em> session must never be
     * able to reach the unscoped variant of an operation, so every caller of an
     * unscoped repository method is required to check this first.
     */
    public boolean isWorkerRequest() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (WORKER_PRINCIPAL.equals(authentication.getName())) {
            return true;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_WORKER".equals(authority.getAuthority()));
    }

    // ── who owns an existing row ──────────────────────────────────────

    public Optional<UUID> ownerOfUserId(UUID userId) {
        return queryOwner("select id from profiles where user_id = ?", userId);
    }

    public Optional<UUID> ownerOfApplication(UUID applicationId) {
        return queryOwner("select profile_id from applications where id = ?", applicationId);
    }

    public Optional<UUID> ownerOfAutomationPlan(UUID planId) {
        return queryOwner("select profile_id from automation_plans where id = ?", planId);
    }

    public Optional<UUID> ownerOfEmail(UUID emailId) {
        return queryOwner("select profile_id from emails where id = ?", emailId);
    }

    public Optional<UUID> ownerOfAccountSession(UUID sessionId) {
        return queryOwner("select owner_profile_id from account_sessions where id = ?", sessionId);
    }

    /**
     * Owner of the email a verification extraction came from. Needed because
     * resolving an OTP is a user-facing action that must not be possible against
     * another account's extracted code.
     */
    public Optional<UUID> ownerOfVerificationExtraction(UUID extractionId) {
        return queryOwner("""
                select e.profile_id from verification_extractions v
                join emails e on e.id = v.source_email_id
                where v.id = ?
                """, extractionId);
    }

    /**
     * The account email behind a profile, so a background action can be audited
     * under the owning account's identity instead of an opaque service label.
     */
    public Optional<String> emailForProfile(UUID profileId) {
        if (profileId == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        select u.email::text from profiles p
                        join users u on u.id = p.user_id
                        where p.id = ?
                        """,
                (rs, rowNum) -> rs.getString(1), profileId)
                .stream().findFirst();
    }

    /**
     * The profile of a user action recorded in the audit trail. {@code actor}
     * holds the acting account's email for user actions (and a non-user label
     * such as SYSTEM for system actions), which is what makes legacy
     * {@code audit_logs} rows attributable even though V008 forbids updating
     * them: attribution is derived at read time instead.
     */
    public Optional<UUID> ownerOfActorEmail(String actor) {
        if (actor == null || actor.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        select p.id from profiles p
                        join users u on u.id = p.user_id
                        where lower(u.email::text) = lower(?)
                        """,
                (rs, rowNum) -> (UUID) rs.getObject(1), actor.trim())
                .stream().findFirst();
    }

    private Optional<UUID> queryOwner(String sql, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        // A NULL owner column is the documented "not attributable" outcome
        // (V022's fail-closed legacy rows), so it maps to Optional.empty().
        // The filter matters: findFirst() throws NullPointerException on a
        // null element, which would turn an unattributed row into a 500 in
        // every background path that resolves ownership from it.
        return jdbcTemplate.query(sql, (rs, rowNum) -> (UUID) rs.getObject(1), id)
                .stream()
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    private static AppUserDetails principalOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserDetails principal)) {
            return null;
        }
        return principal;
    }
}
