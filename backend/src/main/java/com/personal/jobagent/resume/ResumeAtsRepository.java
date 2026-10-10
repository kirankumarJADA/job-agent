package com.personal.jobagent.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.JdbcConversions;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Repository
public class ResumeAtsRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CvArtifactService artifacts;
    public ResumeAtsRepository(JdbcTemplate jdbc, ObjectMapper mapper, CvArtifactService artifacts) {
        this.jdbc = jdbc; this.mapper = mapper; this.artifacts = artifacts;
    }

    public Optional<ResumeAtsAnalysis> find(UUID profileId, UUID jobId, String inputHash) {
        return find(profileId, jobId, inputHash, null);
    }

    public Optional<ResumeAtsAnalysis> find(UUID profileId, UUID jobId, String inputHash, UUID applicationId) {
        String sql = "select r.*, c.body_markdown, c.content_sha256 from resume_ats_analyses r left join cv_versions c on c.id=r.cv_version_id "
                + "where r.profile_id=? and r.job_id=? and r.input_hash=? and ((?::uuid is null and r.application_id is null) or r.application_id=?::uuid)";
        return jdbc.query(sql, (rs,n) -> map(rs), profileId, jobId, inputHash, applicationId, applicationId).stream().findFirst();
    }

    @Transactional
    public ResumeAtsAnalysis insert(ResumeAtsAnalysis a, String title, boolean approved) {
        return insert(a, title, approved, artifacts.renderPdf(a.resumeMarkdown()));
    }

    /**
     * Persists a tailored CV with the EXACT PDF bytes the caller rendered and
     * hashed. Rendering once and storing those bytes is what guarantees the
     * recorded content_sha256 is the hash of the file a user downloads.
     */
    @Transactional
    public ResumeAtsAnalysis insert(ResumeAtsAnalysis a, String title, boolean approved, byte[] pdf) {
        if (a.applicationId() != null) {
            // The application id arrives from the request body, so the
            // existence check must be scoped to the caller's own profile: a
            // foreign id takes the same "Application not found" path as a
            // missing one, and can never be claimed (or have cv_version_id
            // written onto it) by another account.
            UUID applicationJob = jdbc.query("select job_id from applications where id=? and profile_id=?",
                            (rs, n) -> (UUID) rs.getObject(1), a.applicationId(), a.profileId())
                    .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Application not found: " + a.applicationId()));
            if (!a.jobId().equals(applicationJob)) {
                throw new IllegalArgumentException("Application/job mismatch: tailored CV cannot cross job boundaries");
            }
        }
        UUID id = a.id() == null ? UuidV7.generate() : a.id();
        UUID cvId = UuidV7.generate();
        UUID fileId = UuidV7.generate();
        if (pdf == null || pdf.length == 0) throw new IllegalArgumentException("CV_ARTIFACT_EMPTY");
        String artifactSha256 = sha256(pdf);
        if (a.contentSha256() != null && !a.contentSha256().equals(artifactSha256)) {
            throw new IllegalArgumentException("CV_ARTIFACT_CHECKSUM_MISMATCH");
        }
        jdbc.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) values(?, ?, ?, ?, ?, ?, ?)",
                fileId, "cv/" + cvId + ".pdf", artifactSha256, "application/pdf", (long) pdf.length,
                "TAILORED_CV", pdf);
        jdbc.update("""
                insert into cv_versions (id, job_id, application_id, profile_id, kind, title, body_markdown, claims_validation,
                                         approved, profile_revision, profile_snapshot_hash, content_sha256, immutable, pdf_file_id, created_at)
                values (?, ?, ?, ?, 'TAILORED', ?, ?, ?::jsonb, ?, ?, ?, ?, true, ?, now())
                """, cvId, a.jobId(), a.applicationId(), a.profileId(), title, a.resumeMarkdown(),
                JdbcConversions.toJson(Map.of("input_hash", a.inputHash(), "verified_only", true,
                        "profile_revision", a.profileRevision(), "profile_snapshot_hash", a.profileSnapshotHash(),
                        "content_sha256", a.contentSha256(), "ats_report", a.atsReport()), mapper), approved,
                a.profileRevision(), a.profileSnapshotHash(), artifactSha256, fileId);
        jdbc.update("insert into cv_artifact_links(cv_version_id, file_id) values(?, ?) on conflict do nothing", cvId, fileId);
        jdbc.update("""
                insert into resume_ats_analyses (id, profile_id, job_id, application_id, input_hash, role, domain,
                    required_skills, preferred_skills, normalized_skills, verified_evidence, gaps, ats_report,
                    cv_version_id, profile_revision, profile_snapshot_hash)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
                """, id, a.profileId(), a.jobId(), a.applicationId(), a.inputHash(), a.role(), a.domain(),
                json(a.requiredSkills()), json(a.preferredSkills()), json(a.normalizedSkills()), json(a.verifiedEvidence()),
                json(a.gaps()), json(a.atsReport()), cvId, a.profileRevision(), a.profileSnapshotHash());
        linkClaims(cvId, a);
        if (a.applicationId() != null) {
            // Ownership was proven above; profile_id stays in the predicate as
            // defense in depth.
            jdbc.update("update applications set cv_version_id=?, updated_at=now() where id=? and job_id=? and profile_id=?",
                    cvId, a.applicationId(), a.jobId(), a.profileId());
        }
        return aWithCv(a, cvId);
    }

    private void linkClaims(UUID cvId, ResumeAtsAnalysis a) {
        for (Map<String, Object> item : a.verifiedEvidence()) {
            String sourceType = String.valueOf(item.getOrDefault("source_type", item.getOrDefault("source", "PROFILE")));
            UUID sourceId = parseUuid(item.get("evidence_id"));
            String claim = String.valueOf(item.getOrDefault("claim", item.getOrDefault("skill", "verified profile evidence")));
            jdbc.update("""
                    insert into profile_evidence(id, profile_id, source_type, source_id, claim, evidence_status, claim_hash)
                    values (?, ?, ?, ?, ?, 'USER_VERIFIED', md5(?)) on conflict do nothing
                    """, UuidV7.generate(), a.profileId(), sourceType, sourceId, claim, claim);
            jdbc.update("""
                    insert into cv_claim_evidence(id, cv_version_id, source_type, source_id, claim, evidence_status)
                    values (?, ?, ?, ?, ?, 'USER_VERIFIED') on conflict do nothing
                    """, UuidV7.generate(), cvId, sourceType, sourceId, claim);
        }
    }

    private UUID parseUuid(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return UUID.fromString(String.valueOf(value)); } catch (IllegalArgumentException ignored) { return null; }
    }

    private ResumeAtsAnalysis aWithCv(ResumeAtsAnalysis a, UUID cvId) {
        return new ResumeAtsAnalysis(a.id(), a.profileId(), a.jobId(), a.applicationId(), a.inputHash(), a.role(), a.domain(),
                a.requiredSkills(), a.preferredSkills(), a.normalizedSkills(), a.verifiedEvidence(), a.gaps(), a.atsReport(),
                cvId, a.resumeMarkdown(), a.profileRevision(), a.profileSnapshotHash(), a.contentSha256());
    }

    public ResumeAtsAnalysis ensureArtifact(ResumeAtsAnalysis analysis) {
        List<byte[]> existing = jdbc.query("""
                select coalesce(f.content, lf.content) from cv_versions c
                left join files f on f.id=c.pdf_file_id
                left join cv_artifact_links l on l.cv_version_id=c.id
                left join files lf on lf.id=l.file_id
                where c.id=? and c.profile_id=? and coalesce(c.pdf_file_id,l.file_id) is not null
                """, (rs, n) -> rs.getBytes(1), analysis.cvVersionId(), analysis.profileId());
        if (!existing.isEmpty() && existing.get(0) != null) {
            return withContentSha(analysis, sha256(existing.get(0)));
        }
        byte[] pdf = artifacts.renderPdf(analysis.resumeMarkdown());
        UUID fileId = UuidV7.generate();
        jdbc.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) values(?, ?, ?, ?, ?, ?, ?)",
                fileId, "cv/" + analysis.cvVersionId() + ".pdf", sha256(pdf), "application/pdf", (long) pdf.length, "TAILORED_CV", pdf);
        jdbc.update("insert into cv_artifact_links(cv_version_id, file_id) values(?, ?) on conflict do nothing", analysis.cvVersionId(), fileId);
        return withContentSha(analysis, sha256(pdf));
    }

    private ResumeAtsAnalysis withContentSha(ResumeAtsAnalysis analysis, String contentSha) {
        return new ResumeAtsAnalysis(analysis.id(), analysis.profileId(), analysis.jobId(), analysis.applicationId(), analysis.inputHash(),
                analysis.role(), analysis.domain(), analysis.requiredSkills(), analysis.preferredSkills(), analysis.normalizedSkills(),
                analysis.verifiedEvidence(), analysis.gaps(), analysis.atsReport(), analysis.cvVersionId(), analysis.resumeMarkdown(),
                analysis.profileRevision(), analysis.profileSnapshotHash(), contentSha);
    }

    /**
     * The owner's most recent tailored CV for a job. With an application id,
     * only a CV generated for that application qualifies (that is the version
     * the application and execution package use).
     */
    public Optional<ResumeAtsAnalysis> findLatest(UUID profileId, UUID jobId, UUID applicationId) {
        String sql = "select r.*, c.body_markdown, c.content_sha256 from resume_ats_analyses r join cv_versions c on c.id=r.cv_version_id "
                + "where r.profile_id=? and r.job_id=? and c.profile_id=? "
                + "and (?::uuid is null or r.application_id=?::uuid) order by r.created_at desc, r.id desc limit 1";
        return jdbc.query(sql, (rs, n) -> map(rs), profileId, jobId, profileId, applicationId, applicationId).stream().findFirst();
    }

    /** The owner's analysis for one CV version, or empty when the caller does not own it. */
    public Optional<ResumeAtsAnalysis> findByCvVersion(UUID profileId, UUID cvVersionId) {
        String sql = "select r.*, c.body_markdown, c.content_sha256 from resume_ats_analyses r join cv_versions c on c.id=r.cv_version_id "
                + "where c.id=? and c.profile_id=? and r.profile_id=? order by r.created_at desc limit 1";
        return jdbc.query(sql, (rs, n) -> map(rs), cvVersionId, profileId, profileId).stream().findFirst();
    }

    /** Stored bytes plus the digests that must all agree for the artifact to be trusted. */
    public record ArtifactCheck(byte[] bytes, String recordedSha256, String fileSha256, String actualSha256) {
        public boolean intact() {
            return bytes != null && actualSha256 != null && actualSha256.equals(recordedSha256)
                    && (fileSha256 == null || actualSha256.equals(fileSha256));
        }
    }

    public Optional<ArtifactCheck> checkedArtifact(UUID profileId, UUID cvVersionId) {
        return jdbc.query("""
                select c.content_sha256 as recorded, coalesce(f.sha256, lf.sha256) as file_sha,
                       coalesce(f.content, lf.content) as content
                from cv_versions c
                left join files f on f.id=c.pdf_file_id
                left join cv_artifact_links l on l.cv_version_id=c.id
                left join files lf on lf.id=l.file_id
                where c.id=? and c.profile_id=? and c.kind='TAILORED'
                """, (rs, n) -> {
            byte[] content = rs.getBytes("content");
            return new ArtifactCheck(content, rs.getString("recorded"), rs.getString("file_sha"),
                    content == null ? null : sha256(content));
        }, cvVersionId, profileId).stream().findFirst();
    }

    public record Review(boolean approved, String decidedBy, java.time.Instant decidedAt, String contentSha256) {}

    public Optional<Review> review(UUID profileId, UUID cvVersionId) {
        return jdbc.query("select approved, decided_by, decided_at, content_sha256 from cv_version_reviews where cv_version_id=? and profile_id=?",
                (rs, n) -> new Review(rs.getBoolean("approved"), rs.getString("decided_by"),
                        rs.getTimestamp("decided_at").toInstant(), rs.getString("content_sha256")),
                cvVersionId, profileId).stream().findFirst();
    }

    /**
     * Records the owner's review of an immutable CV version. The CV row itself
     * is never updated (V013 trigger); the decision lives beside it and is bound
     * to the artifact digest that was reviewed.
     */
    public void saveReview(UUID profileId, UUID cvVersionId, boolean approved, String actor, String contentSha256) {
        jdbc.update("""
                insert into cv_version_reviews(cv_version_id, profile_id, approved, decided_by, decided_at, content_sha256)
                select c.id, c.profile_id, ?, ?, now(), ? from cv_versions c where c.id=? and c.profile_id=?
                on conflict (cv_version_id) do update set approved=excluded.approved, decided_by=excluded.decided_by,
                    decided_at=now(), content_sha256=excluded.content_sha256
                where cv_version_reviews.profile_id = excluded.profile_id
                """, approved, actor, contentSha256, cvVersionId, profileId);
    }

    public Optional<byte[]> artifact(UUID profileId, UUID cvVersionId) {
        return jdbc.query("""
                select coalesce(f.content, lf.content) from cv_versions c
                left join files f on f.id=c.pdf_file_id
                left join cv_artifact_links l on l.cv_version_id=c.id
                left join files lf on lf.id=l.file_id
                where c.id=? and c.profile_id=? and c.kind='TAILORED'
                """, (rs, n) -> rs.getBytes(1), cvVersionId, profileId).stream().filter(Objects::nonNull).findFirst();
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder();
            for (byte item : digest) value.append(String.format("%02x", item));
            return value.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private String json(Object v) { return JdbcConversions.toJson(v, mapper); }

    @SuppressWarnings("unchecked")
    private ResumeAtsAnalysis map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new ResumeAtsAnalysis((UUID) rs.getObject("id"), (UUID) rs.getObject("profile_id"),
                    (UUID) rs.getObject("job_id"), (UUID) rs.getObject("application_id"), rs.getString("input_hash"),
                    rs.getString("role"), rs.getString("domain"), mapper.readValue(rs.getString("required_skills"), List.class),
                    mapper.readValue(rs.getString("preferred_skills"), List.class), mapper.readValue(rs.getString("normalized_skills"), Map.class),
                    mapper.readValue(rs.getString("verified_evidence"), List.class), mapper.readValue(rs.getString("gaps"), List.class),
                    mapper.readValue(rs.getString("ats_report"), Map.class), (UUID) rs.getObject("cv_version_id"),
                    rs.getString("body_markdown"), rs.getLong("profile_revision"), rs.getString("profile_snapshot_hash"),
                    rs.getString("content_sha256"));
        } catch (Exception e) { throw new IllegalStateException("Invalid resume intelligence JSON", e); }
    }
}
