package com.personal.jobagent.crossfeature;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.jobs.JobMatchService;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.qa.ApplicationAnswerService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 9 — cross-feature integration, proved against live Postgres with
 * the real service graph (no mocks): JobMatchService → CoverLetterService
 * → ApplicationAnswerService → outbox → notification fan-out.
 *
 * The headline assertion is JOB-SPECIFIC PACKAGE ISOLATION: three distinct
 * jobs flow through the full pipeline in the same database at the same
 * time and every artifact (match score, cover letter, answer,
 * notification) must land keyed to exactly its own job — no cross-job
 * contamination.
 *
 * Also proves the fabricated-fact negative path end to end: a question
 * inviting an unverifiable claim produces a HARD_STOP answer and its WARN
 * notification, not a confident fabricated answer.
 */
@SpringBootTest
@Testcontainers
class CrossFeatureIntegrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.events.dispatch-interval-ms", () -> "200");
    }

    @Autowired
    private JobMatchService jobMatchService;
    @Autowired
    private CoverLetterService coverLetterService;
    @Autowired
    private ApplicationAnswerService answerService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record JobFixture(UUID jobId, String company, String title, List<String> skills) {
    }

    /** One job's isolated package: match score + cover letter + answer. */
    private record JobPackage(UUID jobId, String company, String title,
                              int score, UUID coverLetterId, String clTitle, UUID answerId) {
    }

    private JobFixture seedJob(String company, String title, List<String> skills) {
        UUID sourceId = UuidV7.generate();
        jdbcTemplate.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities)
                values (?, 'MANUAL_IMPORT', ?, ?, '{}')
                """, sourceId, "org-" + sourceId, "Test source " + sourceId);
        UUID jobId = UuidV7.generate();
        jdbcTemplate.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  description_text, skills_extracted, status, content_hash)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'DISCOVERED', ?)
                """, jobId, sourceId, "ext-" + jobId, "dedup-" + jobId, company, title,
                "Test description for " + title, skills.toArray(new String[0]), "hash-" + jobId);
        return new JobFixture(jobId, company, title, skills);
    }

    private UUID profileId() {
        // profiles has no created_at (V001); UUIDv7 ids are time-ordered so
        // ordering by id is equivalent.
        return jdbcTemplate.queryForObject("select id from profiles order by id limit 1", UUID.class);
    }

    private void seedCandidateSkills(UUID profileId, List<String> skills) {
        for (String skill : skills) {
            jdbcTemplate.update("""
                    insert into skills (id, profile_id, name, category, mastery)
                    values (?, ?, ?, 'Test', 4)
                    on conflict (profile_id, name) do nothing
                    """, UuidV7.generate(), profileId, skill);
        }
    }

    private int countNotifications(String dedupKey) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from notifications where dedup_key = ?", Integer.class, dedupKey);
        return count != null ? count : 0;
    }

    private void awaitNotification(String dedupKey) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(countNotifications(dedupKey)).isEqualTo(1));
    }

    @Test
    void threeJobsProduceThreeIsolatedPackages() {
        UUID profileId = profileId();
        seedCandidateSkills(profileId, List.of("Java", "Kubernetes", "Go", "Python", "SQL"));

        JobFixture jobA = seedJob("Monzo", "Backend Engineer A", List.of("Java", "Kubernetes"));
        JobFixture jobB = seedJob("Wise", "Platform Engineer B", List.of("Go", "Kubernetes"));
        JobFixture jobC = seedJob("Revolut", "Data Engineer C", List.of("Python", "SQL"));

        JobPackage pkgA = runPipeline(profileId, jobA);
        JobPackage pkgB = runPipeline(profileId, jobB);
        JobPackage pkgC = runPipeline(profileId, jobC);

        // ── isolation: every artifact is keyed to exactly its own job ──
        for (JobPackage pkg : List.of(pkgA, pkgB, pkgC)) {
            Map<String, Object> jobRow = jdbcTemplate.queryForMap(
                    "select status, match_score, match_recommendation, match_breakdown::text as breakdown from jobs where id = ?",
                    pkg.jobId());
            assertThat(jobRow.get("status")).isEqualTo("SCORED");
            assertThat(jobRow.get("match_recommendation")).isEqualTo("APPLY");

            // The job's own cover letter exists, is v1, and carries the job's own title
            Integer clCount = jdbcTemplate.queryForObject("""
                    select count(*) from cover_letters where job_id = ? and version = 1
                    """, Integer.class, pkg.jobId());
            assertThat(clCount).isEqualTo(1);

            String clTitle = jdbcTemplate.queryForObject(
                    "select title from cover_letters where job_id = ? and version = 1", String.class, pkg.jobId());
            assertThat(clTitle).contains(pkg.title());

            // The job's own answer exists and references the same job
            Integer answerCount = jdbcTemplate.queryForObject(
                    "select count(*) from application_answers where job_id = ?", Integer.class, pkg.jobId());
            assertThat(answerCount).isEqualTo(1);
        }

        // ── no cross-job contamination ──
        // Job A's artifacts never appear under job B/C and vice versa: each
        // job owns exactly one cover letter and one answer (asserted above);
        // additionally the titles are pairwise distinct.
        assertThat(pkgA.clTitle()).doesNotContain(pkgB.title()).doesNotContain(pkgC.title());
        assertThat(pkgB.clTitle()).doesNotContain(pkgA.title()).doesNotContain(pkgC.title());
        assertThat(pkgC.clTitle()).doesNotContain(pkgA.title()).doesNotContain(pkgB.title());

        // Notifications: one per business occurrence per job, correlated to
        // the right job — never crossed.
        for (JobPackage pkg : List.of(pkgA, pkgB, pkgC)) {
            awaitNotification("job-matched:" + pkg.jobId());
            awaitNotification("cover-letter-generated:" + pkg.coverLetterId());
            awaitNotification("answer-drafted:" + pkg.answerId());

            UUID notifJobId = jdbcTemplate.queryForObject(
                    "select job_id from notifications where dedup_key = ?",
                    UUID.class, "job-matched:" + pkg.jobId());
            assertThat(notifJobId).isEqualTo(pkg.jobId());

            UUID clNotifJob = jdbcTemplate.queryForObject(
                    "select job_id from notifications where dedup_key = ?",
                    UUID.class, "cover-letter-generated:" + pkg.coverLetterId());
            assertThat(clNotifJob).isEqualTo(pkg.jobId());

            UUID answerNotifJob = jdbcTemplate.queryForObject(
                    "select job_id from notifications where dedup_key = ?",
                    UUID.class, "answer-drafted:" + pkg.answerId());
            assertThat(answerNotifJob).isEqualTo(pkg.jobId());
        }
    }

    private JobPackage runPipeline(UUID profileId, JobFixture job) {
        // 1. MATCH (deterministic scoring + transactional outbox emission)
        JobMatchService.MatchResult match = jobMatchService.evaluateMatch(
                job.jobId(), profileId, null, null);
        assertThat(match.recommendation()).isEqualTo("APPLY");
        assertThat(match.notified()).isTrue();

        // 2. JOB-SPECIFIC COVER LETTER
        CoverLetterService.GenerationResult cl =
                coverLetterService.generateCoverLetter(profileId, job.jobId(), null);
        assertThat(cl.coverLetter().jobId()).isEqualTo(job.jobId());

        // 3. OPEN-ENDED ANSWER
        ApplicationAnswerService.AnswerResult answer = answerService.draftAnswer(
                profileId, job.jobId(), null, "Why do you want to work at " + job.company() + "?");
        assertThat(answer.record().jobId()).isEqualTo(job.jobId());

        return new JobPackage(job.jobId(), job.company(), job.title(),
                match.overall(), cl.coverLetter().id(), cl.coverLetter().title(),
                answer.record().id());
    }

    @Test
    void fabricatedFactAttemptYieldsHardStopNotAConfidentAnswer() {
        UUID profileId = profileId();
        JobFixture job = seedJob("Snyk", "Security Engineer HS", List.of("Java"));
        seedCandidateSkills(profileId, List.of("Java"));

        jobMatchService.evaluateMatch(job.jobId(), profileId, null, null);

        ApplicationAnswerService.AnswerResult result = answerService.draftAnswer(
                profileId, job.jobId(), null, "Describe your experience with top secret clearance programmes");

        assertThat(result.record().status()).isEqualTo("HARD_STOP");

        // The hard stop fans out as a WARN notification keyed to the answer
        awaitNotification("answer-drafted:" + result.record().id());
        String severity = jdbcTemplate.queryForObject(
                "select severity from notifications where dedup_key = ?",
                String.class, "answer-drafted:" + result.record().id());
        String title = jdbcTemplate.queryForObject(
                "select title from notifications where dedup_key = ?",
                String.class, "answer-drafted:" + result.record().id());
        assertThat(severity).isEqualTo("WARN");
        assertThat(title).contains("hard stop");
        assertThat(result.record().jobId()).isEqualTo(job.jobId());
    }
}
