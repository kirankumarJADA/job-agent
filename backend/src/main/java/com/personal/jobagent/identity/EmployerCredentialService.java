package com.personal.jobagent.identity;

import com.personal.jobagent.security.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists and resolves the candidate's employer-portal credentials
 * (Phase 8.5, employer account lifecycle).
 *
 * <p>Username and password are stored AES-256-GCM encrypted (never plaintext,
 * never logged, never sent to an LLM) and are only ever decrypted for the
 * worker that is actively executing THIS candidate's application on THIS
 * employer domain — via the worker-authenticated resolve endpoint. One
 * credential per (profile, employer domain): duplicate portal accounts are
 * prevented by the unique constraint and by {@link #find}.
 */
@Service
public class EmployerCredentialService {

    private final JdbcTemplate db;
    private final SecretCipher cipher;

    public EmployerCredentialService(JdbcTemplate db, SecretCipher cipher) {
        this.db = db;
        this.cipher = cipher;
    }

    public record StoredCredential(UUID id, String employerDomain, String username, String password) {}

    /** Saves or replaces the candidate's credential for one employer domain. */
    public StoredCredential save(UUID profileId, String employerDomain, String username, String password) {
        String encryptedUsername = cipher.encrypt(username);
        String encryptedPassword = cipher.encrypt(password);
        UUID id = UUID.randomUUID();
        db.update("""
                insert into employer_credentials (id, profile_id, employer_domain,
                                                  username_encrypted, password_encrypted)
                values (?, ?, ?, ?, ?)
                on conflict (profile_id, employer_domain) do update set
                    username_encrypted = excluded.username_encrypted,
                    password_encrypted = excluded.password_encrypted,
                    status = 'ACTIVE',
                    updated_at = now()
                """, id, profileId, employerDomain.toLowerCase(), encryptedUsername, encryptedPassword);
        return new StoredCredential(id, employerDomain.toLowerCase(), username, password);
    }

    public Optional<StoredCredential> find(UUID profileId, String employerDomain) {
        List<Map<String, Object>> rows = db.queryForList("""
                select id, username_encrypted, password_encrypted
                from employer_credentials
                where profile_id = ? and employer_domain = ? and status = 'ACTIVE'
                """, profileId, employerDomain.toLowerCase());
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        String username = cipher.decrypt(String.valueOf(row.get("username_encrypted")));
        String password = cipher.decrypt(String.valueOf(row.get("password_encrypted")));
        return Optional.of(new StoredCredential(
                (UUID) row.get("id"), employerDomain.toLowerCase(), username, password));
    }

    public record CredentialSummary(UUID id, String employerDomain, String username, String status,
                                     java.sql.Timestamp updatedAt) {}

    /** Lists the candidate's own saved credentials. Never decrypts or returns the password. */
    public List<CredentialSummary> list(UUID profileId) {
        return db.query("""
                select id, employer_domain, username_encrypted, status, updated_at
                from employer_credentials where profile_id = ? order by employer_domain
                """,
                (rs, rowNum) -> new CredentialSummary(
                        (UUID) rs.getObject("id"), rs.getString("employer_domain"),
                        cipher.decrypt(rs.getString("username_encrypted")),
                        rs.getString("status"), rs.getTimestamp("updated_at")),
                profileId);
    }

    /**
     * Stage 3F (SEC-7): what a worker may resolve credentials for, for one application.
     *
     * @param running          an automation plan for this application is currently RUNNING
     * @param permittedDomains canonical employer hosts the application is actually executing
     *                         against: the persisted job's application URL host and the target
     *                         URL host of its RUNNING plan(s). Exact hosts only — no parent-domain
     *                         or suffix matching, so lookalikes ({@code greenhouse.io.evil.com},
     *                         {@code evilgreenhouse.io}) can never match.
     */
    public record ApplicationBinding(boolean running, java.util.Set<String> permittedDomains) {}

    public ApplicationBinding bindingFor(UUID ownerProfileId, UUID applicationId) {
        java.util.Set<String> domains = new java.util.LinkedHashSet<>();
        List<String> jobUrls = db.queryForList("""
                select j.application_url from applications a join jobs j on j.id = a.job_id
                where a.id = ? and a.profile_id = ?
                """, String.class, applicationId, ownerProfileId);
        jobUrls.forEach(u -> hostDomain(u).ifPresent(domains::add));
        List<String> runningTargets = db.queryForList("""
                select target_url from automation_plans
                where application_id = ? and profile_id = ? and status = 'RUNNING'
                """, String.class, applicationId, ownerProfileId);
        runningTargets.forEach(u -> hostDomain(u).ifPresent(domains::add));
        return new ApplicationBinding(!runningTargets.isEmpty(), java.util.Set.copyOf(domains));
    }

    /** Canonical employer host of an https URL; empty for anything else (http, IP literals, userinfo, junk). */
    static Optional<String> hostDomain(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        try {
            java.net.URI uri = new java.net.URI(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getHost() == null) {
                return Optional.empty();
            }
            return com.personal.jobagent.mailbox.managed.MailAddresses.employerDomain(uri.getHost());
        } catch (java.net.URISyntaxException e) {
            return Optional.empty();
        }
    }

    public boolean exists(UUID profileId, String employerDomain) {
        Integer count = db.queryForObject("""
                select count(*) from employer_credentials
                where profile_id = ? and employer_domain = ? and status = 'ACTIVE'
                """, Integer.class, profileId, employerDomain.toLowerCase());
        return count != null && count > 0;
    }

    public void disable(UUID profileId, String employerDomain) {
        db.update("update employer_credentials set status = 'DISABLED', updated_at = now() "
                + "where profile_id = ? and employer_domain = ?", profileId, employerDomain.toLowerCase());
    }
}
