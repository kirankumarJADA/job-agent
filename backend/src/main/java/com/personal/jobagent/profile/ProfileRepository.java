package com.personal.jobagent.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.JdbcConversions;
import com.personal.jobagent.common.UuidV7;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProfileRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ProfileRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ---- profile core ----
    // NOTE: all RowMapper<X> below are METHODS, not fields. A field
    // initializer here would run before the constructor body assigns
    // objectMapper (Java runs all field initializers before constructor
    // statements, regardless of textual position in the file), which javac
    // correctly rejects as a definite-assignment error for the ones that
    // reference objectMapper directly. Confirmed by a real mvn compile
    // failure on the field version (see PreferenceSetRepository's identical
    // fix) — converted all six uniformly here rather than relying on
    // per-field analysis of which ones the compiler would or wouldn't flag.

    private RowMapper<ProfileRecord> profileRowMapper() {
        return (rs, rowNum) -> new ProfileRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("user_id"),
                rs.getString("headline"),
                rs.getString("phone"),
                rs.getString("location"),
                JdbcConversions.readJsonMap(rs, "work_eligibility", objectMapper),
                JdbcConversions.readJsonMap(rs, "career_goals", objectMapper),
                rs.getString("professional_summary"),
                JdbcConversions.readJsonMap(rs, "links", objectMapper),
                rs.getLong("master_revision"),
                rs.getString("setup_status")
        );
    }

    public Optional<ProfileRecord> findByUserId(UUID userId) {
        return jdbcTemplate.query("select * from profiles where user_id = ?", profileRowMapper(), userId)
                .stream().findFirst();
    }

    public Optional<ProfileRecord> findById(UUID profileId) {
        return jdbcTemplate.query("select * from profiles where id = ?", profileRowMapper(), profileId)
                .stream().findFirst();
    }

    public List<Map<String, Object>> findEvidence(UUID profileId) {
        return jdbcTemplate.queryForList("""
                select id, source_type, source_id, claim, evidence_status, claim_hash, created_at, updated_at
                from profile_evidence where profile_id=? order by created_at, id
                """, profileId);
    }

    public UUID createProfile(UUID userId, String headline, String phone, String location,
                              Map<String, Object> workEligibility, Map<String, Object> careerGoals,
                              String professionalSummary, Map<String, Object> links) {
        UUID id = UuidV7.generate();
        jdbcTemplate.update("""
                insert into profiles (id, user_id, headline, phone, location, work_eligibility,
                                      career_goals, professional_summary, links, master_revision, setup_status)
                values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, 1, 'INCOMPLETE')
                on conflict (user_id) do nothing
                """, id, userId, headline, phone, location,
                JdbcConversions.toJson(workEligibility == null ? Map.of() : workEligibility, objectMapper),
                JdbcConversions.toJson(careerGoals == null ? Map.of() : careerGoals, objectMapper),
                professionalSummary,
                JdbcConversions.toJson(links == null ? Map.of() : links, objectMapper));
        return findByUserId(userId).orElseThrow().id();
    }

    public void markSetupReady(UUID profileId) {
        jdbcTemplate.update("update profiles set setup_status='READY', updated_at=now() where id=?", profileId);
    }

    public void updateProfile(UUID profileId, String headline, String phone, String location,
                               Map<String, Object> workEligibility, Map<String, Object> careerGoals,
                               String professionalSummary, Map<String, Object> links) {
        jdbcTemplate.update("""
                        update profiles set headline = ?, phone = ?, location = ?,
                            work_eligibility = ?::jsonb, career_goals = ?::jsonb,
                            professional_summary = ?, links = ?::jsonb,
                            master_revision = master_revision + 1, setup_status = 'INCOMPLETE', updated_at = now()
                        where id = ?
                        """,
                headline, phone, location,
                JdbcConversions.toJson(workEligibility == null ? Map.of() : workEligibility, objectMapper),
                JdbcConversions.toJson(careerGoals == null ? Map.of() : careerGoals, objectMapper),
                professionalSummary,
                JdbcConversions.toJson(links == null ? Map.of() : links, objectMapper),
                profileId);
    }

    // ---- work experiences ----

    private RowMapper<WorkExperienceRecord> experienceRowMapper() {
        return (rs, rowNum) -> new WorkExperienceRecord(
                (UUID) rs.getObject("id"), (UUID) rs.getObject("profile_id"),
                rs.getString("company"), rs.getString("title"),
                rs.getDate("start_month") != null ? rs.getDate("start_month").toLocalDate() : null,
                rs.getDate("end_month") != null ? rs.getDate("end_month").toLocalDate() : null,
                rs.getString("location"),
                readBulletsList(rs, "bullets"),
                rs.getInt("sort_order")
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readBulletsList(java.sql.ResultSet rs, String column) {
        try {
            String json = rs.getString(column);
            if (json == null) {
                return List.of();
            }
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse bullets jsonb", e);
        }
    }

    public List<WorkExperienceRecord> findExperiences(UUID profileId) {
        return jdbcTemplate.query(
                "select * from work_experiences where profile_id = ? order by sort_order",
                experienceRowMapper(), profileId);
    }

    public UUID insertExperience(UUID profileId, String company, String title,
                                  LocalDate startMonth, LocalDate endMonth, String location,
                                  List<Map<String, Object>> bullets, int sortOrder) {
        UUID id = UuidV7.generate();
        jdbcTemplate.update("""
                        insert into work_experiences (id, profile_id, company, title, start_month, end_month, location, bullets, sort_order)
                        values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        """,
                id, profileId, company, title,
                Date.valueOf(startMonth), endMonth != null ? Date.valueOf(endMonth) : null, location,
                JdbcConversions.toJson(bullets, objectMapper), sortOrder);
        return id;
    }

    public boolean updateExperience(UUID id, UUID profileId, String company, String title,
                                    LocalDate startMonth, LocalDate endMonth, String location,
                                    List<Map<String, Object>> bullets, int sortOrder) {
        return jdbcTemplate.update("""
                update work_experiences set company=?, title=?, start_month=?, end_month=?, location=?,
                    bullets=?::jsonb, sort_order=? where id=? and profile_id=?
                """, company, title, Date.valueOf(startMonth), endMonth == null ? null : Date.valueOf(endMonth),
                location, JdbcConversions.toJson(bullets == null ? List.of() : bullets, objectMapper), sortOrder, id, profileId) > 0;
    }

    public boolean deleteExperience(UUID id, UUID profileId) {
        return jdbcTemplate.update("delete from work_experiences where id = ? and profile_id = ?", id, profileId) > 0;
    }

    // ---- education ----

    private RowMapper<EducationRecord> educationRowMapper() {
        return (rs, rowNum) -> new EducationRecord(
                (UUID) rs.getObject("id"), (UUID) rs.getObject("profile_id"),
                rs.getString("institution"), rs.getString("qualification"), rs.getString("field"),
                (Integer) rs.getObject("start_year"), (Integer) rs.getObject("end_year"), rs.getString("grade")
        );
    }

    public List<EducationRecord> findEducation(UUID profileId) {
        return jdbcTemplate.query("select * from education where profile_id = ?", educationRowMapper(), profileId);
    }

    public UUID insertEducation(UUID profileId, String institution, String qualification, String field,
                                 Integer startYear, Integer endYear, String grade) {
        UUID id = UuidV7.generate();
        jdbcTemplate.update("""
                        insert into education (id, profile_id, institution, qualification, field, start_year, end_year, grade)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, profileId, institution, qualification, field, startYear, endYear, grade);
        return id;
    }

    public boolean updateEducation(UUID id, UUID profileId, String institution, String qualification,
                                   String field, Integer startYear, Integer endYear, String grade) {
        return jdbcTemplate.update("""
                update education set institution=?, qualification=?, field=?, start_year=?, end_year=?, grade=?
                where id=? and profile_id=?
                """, institution, qualification, field, startYear, endYear, grade, id, profileId) > 0;
    }

    public boolean deleteEducation(UUID id, UUID profileId) {
        return jdbcTemplate.update("delete from education where id = ? and profile_id = ?", id, profileId) > 0;
    }

    // ---- skills (the resource with real constraints worth exercising: mastery 1-5, unique name per profile) ----

    private RowMapper<SkillRecord> skillRowMapper() {
        return (rs, rowNum) -> new SkillRecord(
                (UUID) rs.getObject("id"), (UUID) rs.getObject("profile_id"),
                rs.getString("name"), rs.getString("category"),
                (Integer) rs.getObject("mastery"),
                rs.getBigDecimal("years"),
                (UUID) rs.getObject("evidence_experience_id")
        );
    }

    public List<SkillRecord> findSkills(UUID profileId) {
        return jdbcTemplate.query("select * from skills where profile_id = ?", skillRowMapper(), profileId);
    }

    public static final class DuplicateSkillException extends RuntimeException {
        public DuplicateSkillException(String name) {
            super("A skill named '" + name + "' already exists for this profile");
        }
    }

    public static final class InvalidMasteryException extends RuntimeException {
        public InvalidMasteryException(int mastery) {
            super("mastery must be between 1 and 5, got " + mastery);
        }
    }

    public UUID insertSkill(UUID profileId, String name, String category, Integer mastery,
                             BigDecimal years, UUID evidenceExperienceId) {
        if (mastery != null && (mastery < 1 || mastery > 5)) {
            // API-layer check ahead of the DB CHECK constraint, so the
            // caller gets a clean 400 rather than a raw Postgres error —
            // per the API contract's validation requirements.
            throw new InvalidMasteryException(mastery);
        }
        UUID id = UuidV7.generate();
        try {
            jdbcTemplate.update("""
                            insert into skills (id, profile_id, name, category, mastery, years, evidence_experience_id)
                            values (?, ?, ?, ?, ?, ?, ?)
                            """,
                    id, profileId, name, category, mastery, years, evidenceExperienceId);
        } catch (DataIntegrityViolationException e) {
            // unique(profile_id, name) violation
            throw new DuplicateSkillException(name);
        }
        return id;
    }

    public boolean updateSkill(UUID id, UUID profileId, String name, String category, Integer mastery,
                               BigDecimal years, UUID evidenceExperienceId) {
        if (mastery != null && (mastery < 1 || mastery > 5)) throw new InvalidMasteryException(mastery);
        return jdbcTemplate.update("""
                update skills set name=?, category=?, mastery=?, years=?, evidence_experience_id=?
                where id=? and profile_id=?
                """, name, category, mastery, years, evidenceExperienceId, id, profileId) > 0;
    }

    public boolean deleteSkill(UUID id, UUID profileId) {
        return jdbcTemplate.update("delete from skills where id = ? and profile_id = ?", id, profileId) > 0;
    }

    // ---- projects ----

    private RowMapper<ProjectRecord> projectRowMapper() {
        return (rs, rowNum) -> new ProjectRecord(
                (UUID) rs.getObject("id"), (UUID) rs.getObject("profile_id"),
                rs.getString("name"), rs.getString("summary"), rs.getString("url"),
                readBulletsList(rs, "bullets"), rs.getInt("sort_order")
        );
    }

    public List<ProjectRecord> findProjects(UUID profileId) {
        return jdbcTemplate.query("select * from projects where profile_id = ? order by sort_order", projectRowMapper(), profileId);
    }

    public UUID insertProject(UUID profileId, String name, String summary, String url,
                               List<Map<String, Object>> bullets, int sortOrder) {
        UUID id = UuidV7.generate();
        jdbcTemplate.update("""
                        insert into projects (id, profile_id, name, summary, url, bullets, sort_order)
                        values (?, ?, ?, ?, ?, ?::jsonb, ?)
                        """,
                id, profileId, name, summary, url, JdbcConversions.toJson(bullets, objectMapper), sortOrder);
        return id;
    }

    public boolean updateProject(UUID id, UUID profileId, String name, String summary, String url,
                                 List<Map<String, Object>> bullets, int sortOrder) {
        return jdbcTemplate.update("""
                update projects set name=?, summary=?, url=?, bullets=?::jsonb, sort_order=?
                where id=? and profile_id=?
                """, name, summary, url, JdbcConversions.toJson(bullets == null ? List.of() : bullets, objectMapper), sortOrder, id, profileId) > 0;
    }

    public boolean deleteProject(UUID id, UUID profileId) {
        return jdbcTemplate.update("delete from projects where id = ? and profile_id = ?", id, profileId) > 0;
    }

    // ---- certifications ----

    private RowMapper<CertificationRecord> certificationRowMapper() {
        return (rs, rowNum) -> new CertificationRecord(
                (UUID) rs.getObject("id"), (UUID) rs.getObject("profile_id"),
                rs.getString("name"), rs.getString("issuer"),
                rs.getDate("issued_on") != null ? rs.getDate("issued_on").toLocalDate() : null,
                rs.getString("credential_id")
        );
    }

    public List<CertificationRecord> findCertifications(UUID profileId) {
        return jdbcTemplate.query("select * from certifications where profile_id = ?", certificationRowMapper(), profileId);
    }

    public UUID insertCertification(UUID profileId, String name, String issuer, LocalDate issuedOn, String credentialId) {
        UUID id = UuidV7.generate();
        jdbcTemplate.update("""
                        insert into certifications (id, profile_id, name, issuer, issued_on, credential_id)
                        values (?, ?, ?, ?, ?, ?)
                        """,
                id, profileId, name, issuer, issuedOn != null ? Date.valueOf(issuedOn) : null, credentialId);
        return id;
    }

    public boolean updateCertification(UUID id, UUID profileId, String name, String issuer,
                                       LocalDate issuedOn, String credentialId) {
        return jdbcTemplate.update("""
                update certifications set name=?, issuer=?, issued_on=?, credential_id=?
                where id=? and profile_id=?
                """, name, issuer, issuedOn == null ? null : Date.valueOf(issuedOn), credentialId, id, profileId) > 0;
    }

    public boolean deleteCertification(UUID id, UUID profileId) {
        return jdbcTemplate.update("delete from certifications where id = ? and profile_id = ?", id, profileId) > 0;
    }
}
