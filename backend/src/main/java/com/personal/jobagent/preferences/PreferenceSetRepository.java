package com.personal.jobagent.preferences;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.JdbcConversions;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PreferenceSetRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PreferenceSetRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // Method, not a field: a field initializer here would run before the
    // constructor body assigns objectMapper (Java runs all field
    // initializers before constructor statements, regardless of textual
    // position), which javac correctly rejects as a definite-assignment
    // error. A method sidesteps the ordering issue entirely — confirmed by
    // a real mvn compile failure on the field version.
    private RowMapper<PreferenceSetRecord> rowMapper() {
        return (rs, rowNum) -> new PreferenceSetRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("profile_id"),
                JdbcConversions.readStringArray(rs, "titles"),
                JdbcConversions.readStringArray(rs, "keywords_include"),
                JdbcConversions.readStringArray(rs, "keywords_exclude"),
                JdbcConversions.readStringArray(rs, "required_skills"),
                JdbcConversions.readStringArray(rs, "locations_allowed"),
                JdbcConversions.readStringArray(rs, "remote_types"),
                JdbcConversions.readStringArray(rs, "employment_types"),
                JdbcConversions.readStringArray(rs, "experience_levels"),
                rs.getObject("salary_min_gbp") != null ? rs.getLong("salary_min_gbp") : null,
                rs.getString("sponsorship_policy"),
                JdbcConversions.readStringArray(rs, "company_size_pref"),
                JdbcConversions.readStringArray(rs, "industry_pref"),
                rs.getString("application_mode"),
                JdbcConversions.readJsonMap(rs, "scoring_weights", objectMapper),
                JdbcConversions.readJsonMap(rs, "extra_filters", objectMapper),
                rs.getBoolean("is_active")
        );
    }

    public Optional<PreferenceSetRecord> findActiveByProfileId(UUID profileId) {
        List<PreferenceSetRecord> results = jdbcTemplate.query(
                "select * from preference_sets where profile_id = ? and is_active = true limit 1",
                rowMapper(), profileId);
        return results.stream().findFirst();
    }

    /**
     * Full replacement of the active preference set's fields, per the API
     * contract's PUT semantics. Caller (PreferencesController) is
     * responsible for calling ScoringWeights.validate() first — this
     * method assumes the weights already passed validation.
     */
    public void update(UUID id, PreferenceSetRecord updated) {
        jdbcTemplate.execute((Connection connection) -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    update preference_sets set
                        titles = ?, keywords_include = ?, keywords_exclude = ?, required_skills = ?,
                        locations_allowed = ?, remote_types = ?, employment_types = ?, experience_levels = ?,
                        salary_min_gbp = ?, sponsorship_policy = ?, company_size_pref = ?, industry_pref = ?,
                        application_mode = ?, scoring_weights = ?::jsonb, extra_filters = ?::jsonb,
                        updated_at = now()
                    where id = ?
                    """)) {
                Array titles = JdbcConversions.toSqlArray(connection, updated.titles());
                Array keywordsInclude = JdbcConversions.toSqlArray(connection, updated.keywordsInclude());
                Array keywordsExclude = JdbcConversions.toSqlArray(connection, updated.keywordsExclude());
                Array requiredSkills = JdbcConversions.toSqlArray(connection, updated.requiredSkills());
                Array locationsAllowed = JdbcConversions.toSqlArray(connection, updated.locationsAllowed());
                Array remoteTypes = JdbcConversions.toSqlArray(connection, updated.remoteTypes());
                Array employmentTypes = JdbcConversions.toSqlArray(connection, updated.employmentTypes());
                Array experienceLevels = JdbcConversions.toSqlArray(connection, updated.experienceLevels());
                Array companySizePref = JdbcConversions.toSqlArray(connection, updated.companySizePref());
                Array industryPref = JdbcConversions.toSqlArray(connection, updated.industryPref());

                ps.setArray(1, titles);
                ps.setArray(2, keywordsInclude);
                ps.setArray(3, keywordsExclude);
                ps.setArray(4, requiredSkills);
                ps.setArray(5, locationsAllowed);
                ps.setArray(6, remoteTypes);
                ps.setArray(7, employmentTypes);
                ps.setArray(8, experienceLevels);
                if (updated.salaryMinGbp() != null) {
                    ps.setLong(9, updated.salaryMinGbp());
                } else {
                    ps.setNull(9, java.sql.Types.BIGINT);
                }
                ps.setString(10, updated.sponsorshipPolicy());
                ps.setArray(11, companySizePref);
                ps.setArray(12, industryPref);
                ps.setString(13, updated.applicationMode());
                ps.setString(14, JdbcConversions.toJson(updated.scoringWeights(), objectMapper));
                ps.setString(15, JdbcConversions.toJson(updated.extraFilters(), objectMapper));
                ps.setObject(16, id);
                return ps.executeUpdate();
            }
        });
    }
}
