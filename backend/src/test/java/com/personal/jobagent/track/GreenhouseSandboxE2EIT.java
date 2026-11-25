package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.GreenhouseAdapter;
import com.personal.jobagent.automation.GreenhouseExecutionPlanService;
import com.personal.jobagent.discovery.platform.AddressGuard;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 2.3 vertical-slice integration test for Greenhouse sandbox acquisition:
 * Candidate sandbox registration -> Greenhouse execution plan -> human approval ->
 * prepare-submit -> authenticated worker claim & completion with captured canonical DOM/network
 * evidence -> encrypted evidence at rest -> verified receipt -> confirmed tracking view.
 *
 * Uses Testcontainers PostgreSQL 16 and local Greenhouse HTML fixtures; never touches real employers.
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.rate-limit.enabled=false",
        "app.worker-event-token=" + GreenhouseSandboxE2EIT.WORKER_TOKEN,
        "app.secrets.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.sandbox.allowed-domains=boards.greenhouse.io,job-boards.greenhouse.io",
        "app.sandbox.allowed-path-prefixes=/robin-sandbox/,/sandbox/",
        "app.sandbox.require-https=true",
        "app.sandbox.allow-local-test-hosts=false"
})
@AutoConfigureMockMvc
@Testcontainers
class GreenhouseSandboxE2EIT {

    static final String WORKER_TOKEN = "gh-sandbox-e2e-worker-token";
    private static final UUID GH_SOURCE = UUID.randomUUID();
    private static final String GH_SANDBOX_URL = "https://boards.greenhouse.io/robin-sandbox/jobs/8200681";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @TestConfiguration
    static class LocalFixtureConfig {
        @Bean
        @Primary
        AddressGuard fixtureAddressGuard() {
            return new AddressGuard(host -> {
                if ("boards.greenhouse.io".equalsIgnoreCase(host)
                        || "job-boards.greenhouse.io".equalsIgnoreCase(host)) {
                    return new InetAddress[] { InetAddress.getByName("151.101.1.69") };
                }
                throw new UnknownHostException(host);
            });
        }

        @Bean
        @Primary
        GreenhouseAdapter fixtureGreenhouseAdapter() {
            String html = """
                    <!doctype html>
                    <html>
                    <body>
                    <div id="application">
                      <form id="application_form" action="/robin-sandbox/jobs/8200681" method="post">
                        <div class="field">
                          <input class="remix-css-requiredInput" required="" aria-hidden="true" tabindex="-1" value=""/>
                          <label id="first_name-label" for="first_name" class="label">First name<span>*</span></label>
                          <input id="first_name" class="input" type="text"/>
                        </div>
                        <div class="field">
                          <input class="remix-css-requiredInput" required="" aria-hidden="true" tabindex="-1" value=""/>
                          <label id="last_name-label" for="last_name" class="label">Last name<span>*</span></label>
                          <input id="last_name" class="input" type="text"/>
                        </div>
                        <div class="field">
                          <input class="remix-css-requiredInput" required="" aria-hidden="true" tabindex="-1" value=""/>
                          <label id="email-label" for="email" class="label">Email address<span>*</span></label>
                          <input id="email" class="input" type="email"/>
                        </div>
                        <div class="field">
                          <input class="remix-css-requiredInput" required="" aria-hidden="true" tabindex="-1" value=""/>
                          <label id="phone-label" for="phone" class="label">Phone<span>*</span></label>
                          <input id="phone" class="input" type="tel"/>
                        </div>
                        <div class="field upload">
                          <label for="resume" class="label">Resume</label>
                          <input id="resume" type="file" accept=".pdf"/>
                        </div>
                        <button type="submit">Submit Application</button>
                      </form>
                    </div>
                    </body>
                    </html>
                    """;
            return new GreenhouseAdapter(url -> html);
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private GreenhouseExecutionPlanService greenhousePlans;

    private MockHttpSession devSession;
    private UUID devProfileId;
    private MockHttpSession otherSession;
    private UUID otherProfileId;

    @BeforeEach
    void setUp() throws Exception {
        devSession = login("dev@example.local", "DevPassword123!");
        devProfileId = jdbc.queryForObject(
                "select p.id from profiles p join users u on u.id = p.user_id where u.email = 'dev@example.local'",
                UUID.class);
        jdbc.update("update profiles set phone = '+447700900123' where id = ?", devProfileId);

        String devHash = jdbc.queryForObject(
                "select password_hash from users where email = 'dev@example.local'", String.class);
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values ('00000000-0000-7000-8000-000000000078', 'other-gh-sbx@example.local', ?, 'Other Sandbox User', 'LOCAL')
                on conflict (email) do update set password_hash = excluded.password_hash
                """, devHash);
        jdbc.update("""
                insert into profiles (id, user_id, setup_status, phone, location, links)
                values ('00000000-0000-7000-8000-000000000079', '00000000-0000-7000-8000-000000000078', 'READY', '+447700900124', null, '{}')
                on conflict (id) do nothing
                """);
        otherProfileId = UUID.fromString("00000000-0000-7000-8000-000000000079");
        otherSession = login("other-gh-sbx@example.local", "DevPassword123!");

        jdbc.update("""
                delete from candidate_sandbox_registrations r
                where r.profile_id in (?, ?)
                  and not exists (select 1 from submission_receipts sr where sr.sandbox_registration_id = r.id)
                """, devProfileId, otherProfileId);
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'robin-sandbox', 'Robin Greenhouse Sandbox', '{}', 'DISCOVERY_ONLY')
                on conflict (id) do nothing
                """, GH_SOURCE);
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private record SeededPlan(UUID applicationId, UUID planId) {}

    private SeededPlan prepareRealGreenhousePlan(String suffix, String postingUrl) throws Exception {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  location_raw, description_text, skills_extracted, status, content_hash,
                                  application_url)
                values (?, ?, ?, ?, 'Robin Sandbox', ?, 'Remote', 'Sandbox Role', '{}'::text[], 'DISCOVERED', ?, ?)
                """, jobId, GH_SOURCE, "gh-" + suffix, "dedup-" + suffix, "Sandbox Engineer " + suffix,
                "hash-" + suffix, postingUrl);

        UUID appId = UUID.randomUUID();
        jdbc.update("""
                insert into applications (id, job_id, profile_id, mode, identity_key, status)
                values (?, ?, ?, 'ASSISTED', ?, 'APPLICATION_STARTED')
                """, appId, jobId, devProfileId, "gh-app-" + suffix);

        UUID fileId = UUID.randomUUID();
        UUID cvId = UUID.randomUUID();
        byte[] pdf = "%PDF-1.4\n%% sandbox resume\n".getBytes(StandardCharsets.UTF_8);
        String cvSha = sha256Hex(pdf);
        jdbc.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) values(?,?,?,?,?,?,?)",
                fileId, "cv/" + cvId + ".pdf", cvSha, "application/pdf", (long) pdf.length, "TAILORED_CV", pdf);
        jdbc.update("""
                insert into cv_versions (id, job_id, application_id, profile_id, kind, title, body_markdown,
                                         claims_validation, approved, profile_revision, profile_snapshot_hash,
                                         content_sha256, immutable, pdf_file_id, created_at)
                values (?, ?, ?, ?, 'TAILORED', 'Sandbox CV', 'CV markdown', '{"passed":true}', true, 1, 'snap', ?, true, ?, now())
                """, cvId, jobId, appId, devProfileId, cvSha, fileId);
        jdbc.update("update applications set cv_version_id = ? where id = ?", cvId, appId);
        jdbc.update("""
                insert into resume_ats_analyses (id, profile_id, job_id, application_id, input_hash, role, domain,
                    required_skills, preferred_skills, normalized_skills, verified_evidence, gaps, ats_report,
                    cv_version_id, profile_revision, profile_snapshot_hash)
                values (?, ?, ?, ?, ?, 'Engineer', 'Tech', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?::jsonb, ?, 1, 'snap')
                """, UUID.randomUUID(), devProfileId, jobId, appId, "hash-" + cvId,
                json.writeValueAsString(Map.of("validation", Map.of(
                        "passed", true, "validator_version", com.personal.jobagent.documents.DocumentFactValidator.VERSION))), cvId);
        jdbc.update("""
                insert into cv_version_reviews (cv_version_id, profile_id, approved, decided_by, content_sha256)
                values (?, ?, true, 'dev@example.local', ?)
                on conflict (cv_version_id) do nothing
                """, cvId, devProfileId, cvSha);

        UUID planId = greenhousePlans.createExecutionPlan(devProfileId, appId, jobId).orElseThrow();
        // Complete prep stage cleanly as worker
        claimAndMoveToAwaitingApproval(planId);
        // Human approval + explicit submit queuing
        mvc.perform(post("/api/v1/automation/plans/" + planId + "/approve-submit").session(devSession).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/" + planId + "/prepare-submit").session(devSession).with(csrf()))
                .andExpect(status().isOk());
        // Worker claims prepared REAL_SUBMIT_CLICK plan
        mvc.perform(post("/api/v1/automation/plans/" + planId + "/claim")
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk());
        return new SeededPlan(appId, planId);
    }

    private void claimAndMoveToAwaitingApproval(UUID planId) throws Exception {
        mvc.perform(post("/api/v1/automation/plans/" + planId + "/claim")
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/" + planId + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "outcome", "HUMAN_REQUIRED",
                                "detail", "Form preparation finished",
                                "result", List.of()))))
                .andExpect(status().isNoContent());
    }

    private String canonicalGreenhouseEvidence(UUID planId, UUID appId, String confId, String extraMarker) throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("atsPlatform", "GREENHOUSE");
        raw.put("attemptCorrelation", Map.of("applicationId", appId.toString(), "planId", planId.toString()));
        raw.put("confirmationContainerHtml", "<div class=\"confirmation\">Application submitted! "
                + (confId != null ? "Confirmation ID: " + confId : "Thank you") + "</div>");
        raw.put("confirmationNumber", confId);
        raw.put("confirmationPageUrl", GH_SANDBOX_URL + "/confirmation");
        raw.put("schemaVersion", "robin-submission-evidence-v1");
        if ("fixture".equals(extraMarker)) {
            raw.put("fixture", true);
        }
        return json.writeValueAsString(raw);
    }

    private Map<String, Object> completionBody(String confId, List<String> signals, String rawEvidence) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "SUBMISSION_EVIDENCE");
        entry.put("planType", "GREENHOUSE");
        entry.put("category", "CONFIRMED");
        entry.put("confirmed", true);
        entry.put("evidence", "2xx submission POST and confirmation page container observed.");
        if (confId != null) {
            entry.put("confirmationNumber", confId);
        }
        entry.put("signals", signals);
        if (rawEvidence != null) {
            entry.put("rawEvidence", rawEvidence);
        }
        return Map.of(
                "outcome", "SUBMITTED",
                "detail", "Greenhouse submission confirmed",
                "result", List.of(entry));
    }

    @Test
    void fullGreenhouseSandboxVerticalSliceSucceedsEndToEnd() throws Exception {
        // 1. Candidate registers sandbox posting via authenticated session
        mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(devSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(GH_SANDBOX_URL)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permittedDomain").value("boards.greenhouse.io"));

        // 2. Prepare + approve + queue + claim real Greenhouse submission
        SeededPlan seeded = prepareRealGreenhousePlan("e2e-ok-" + UUID.randomUUID(), GH_SANDBOX_URL);
        String raw = canonicalGreenhouseEvidence(seeded.planId(), seeded.applicationId(), "GH-SBX-849201", null);
        List<String> signals = List.of(
                "GREENHOUSE_SUBMISSION_RESPONSE_2XX",
                "GREENHOUSE_CONFIRMATION_URL_MATCHED",
                "GREENHOUSE_CONFIRMATION_CONTAINER_PRESENT",
                "EMPLOYER_CONFIRMATION_ID_OBSERVED");

        // 3. Authenticated worker submits captured DOM/network evidence
        mvc.perform(post("/api/v1/automation/plans/" + seeded.planId() + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completionBody("GH-SBX-849201", signals, raw))))
                .andExpect(status().isNoContent());

        // 4. Receipt endpoint returns VERIFIED confirmation backed by encrypted raw evidence + sandbox
        mvc.perform(get("/api/v1/applications/" + seeded.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atsKind").value("GREENHOUSE"))
                .andExpect(jsonPath("$.confirmation.confirmed").value(true))
                .andExpect(jsonPath("$.confirmation.confirmationId").value("GH-SBX-849201"))
                .andExpect(jsonPath("$.reconciliation.required").value(false));

        // 5. Tracking endpoint reflects VERIFIED submission
        mvc.perform(get("/api/v1/applications/" + seeded.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.basis").value("VERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("CONFIRMED"));

        // 6. Owner raw evidence endpoint returns decrypted DOM evidence matching server SHA-256
        String expectedSha = sha256Hex(raw.getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/applications/" + seeded.applicationId() + "/receipts/" + seeded.planId() + "/evidence")
                        .session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").value(expectedSha))
                .andExpect(jsonPath("$.payload").value(raw));

        // 7. Replaying completion on already-SUBMITTED plan is rejected (409)
        mvc.perform(post("/api/v1/automation/plans/" + seeded.planId() + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completionBody("GH-SBX-849201", signals, raw))))
                .andExpect(status().isConflict());
    }

    @Test
    void revokedOrMismatchedSandboxRegistrationAndMissingOrSyntheticIdsKeepReceiptUnverified() throws Exception {
        String testSandboxUrl = "https://boards.greenhouse.io/robin-sandbox/jobs/8200682";
        // A. Missing confirmation ID leaves receipt UNVERIFIED even with valid active sandbox
        mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(devSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(testSandboxUrl)))
                .andExpect(status().isCreated());

        SeededPlan noCid = prepareRealGreenhousePlan("no-cid-" + UUID.randomUUID(), testSandboxUrl);
        String rawNoCid = canonicalGreenhouseEvidence(noCid.planId(), noCid.applicationId(), null, null);
        mvc.perform(post("/api/v1/automation/plans/" + noCid.planId() + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completionBody(null,
                                List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX", "GREENHOUSE_CONFIRMATION_CONTAINER_PRESENT"),
                                rawNoCid))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/applications/" + noCid.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true));
        mvc.perform(get("/api/v1/applications/" + noCid.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("UNVERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("UNVERIFIED"));

        // B. Synthetic fixture marker or FIXTURE-/REQ-/internal/URL posting ID stays UNVERIFIED
        for (String[] badCase : new String[][] {
                { "FIXTURE-999", "fixture" },
                { "REQ-999", "" },
                { "8200682", "" },
                { "__APP_ID__", "" }
        }) {
            SeededPlan rejectedPlan = prepareRealGreenhousePlan("bad-id-" + UUID.randomUUID(), testSandboxUrl);
            String confId = "__APP_ID__".equals(badCase[0]) ? rejectedPlan.applicationId().toString() : badCase[0];
            String extraMarker = badCase[1].isEmpty() ? null : badCase[1];
            String badRaw = canonicalGreenhouseEvidence(rejectedPlan.planId(), rejectedPlan.applicationId(), confId, extraMarker);
            mvc.perform(post("/api/v1/automation/plans/" + rejectedPlan.planId() + "/complete")
                            .header("Authorization", "Bearer " + WORKER_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(completionBody(confId,
                                    List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX"), badRaw))))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/applications/" + rejectedPlan.applicationId() + "/receipt").session(devSession))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                    .andExpect(jsonPath("$.reconciliation.required").value(true));
        }

        // C. Oversized payload (> 512KB) records REJECTED_TOO_LARGE and stays UNVERIFIED
        SeededPlan oversized = prepareRealGreenhousePlan("oversized-" + UUID.randomUUID(), testSandboxUrl);
        String hugePayload = "<div class=\"confirmation\">" + "x".repeat(SubmissionEvidenceRepository.MAX_RAW_EVIDENCE_BYTES + 256) + "</div>";
        mvc.perform(post("/api/v1/automation/plans/" + oversized.planId() + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completionBody("GH-SBX-HUGE1",
                                List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX"), hugePayload))))
                .andExpect(status().isNoContent());
        String rawStatus = jdbc.queryForObject(
                "select raw_evidence_status from submission_evidence where plan_id = ?",
                String.class, oversized.planId());
        assertThat(rawStatus).isEqualTo(SubmissionEvidenceRepository.RAW_TOO_LARGE);
        mvc.perform(get("/api/v1/applications/" + oversized.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("UNVERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("UNVERIFIED"));

        // D. Revoking registration refuses verification
        UUID regId = jdbc.queryForObject(
                "select id from candidate_sandbox_registrations where profile_id = ? and posting_url = ?",
                UUID.class, devProfileId, testSandboxUrl);
        mvc.perform(post("/api/v1/sandbox-registrations/" + regId + "/revoke").session(devSession).with(csrf()))
                .andExpect(status().isOk());

        SeededPlan afterRevoke = prepareRealGreenhousePlan("revoked-" + UUID.randomUUID(), testSandboxUrl);
        String rawAfterRevoke = canonicalGreenhouseEvidence(afterRevoke.planId(), afterRevoke.applicationId(), "GH-SBX-REV1", null);
        mvc.perform(post("/api/v1/automation/plans/" + afterRevoke.planId() + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completionBody("GH-SBX-REV1",
                                List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX"), rawAfterRevoke))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/applications/" + afterRevoke.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true));
        mvc.perform(get("/api/v1/applications/" + afterRevoke.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("UNVERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("UNVERIFIED"));

        // E. Forged worker completion without worker token is refused (403)
        mvc.perform(post("/api/v1/automation/plans/" + afterRevoke.planId() + "/complete")
                        .session(devSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completionBody("GH-SBX-FORGE",
                                List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX"), rawAfterRevoke))))
                .andExpect(status().isForbidden());
    }

    @Test
    void registeredDifferentPostingUrlCannotVerifySubmission() throws Exception {
        String targetUrl = "https://boards.greenhouse.io/robin-sandbox/jobs/8200683";
        // 0. Completely unregistered sandbox also fails closed
        SeededPlan unregistered = prepareRealGreenhousePlan("unreg-" + UUID.randomUUID(), targetUrl);
        String rawUnreg = canonicalGreenhouseEvidence(unregistered.planId(), unregistered.applicationId(), "GH-SBX-UNREG1", null);
        mvc.perform(post("/api/v1/automation/plans/" + unregistered.planId() + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completionBody("GH-SBX-UNREG1",
                                List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX"), rawUnreg))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/applications/" + unregistered.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true));

        String otherUrl = "https://boards.greenhouse.io/robin-sandbox/jobs/9999999";
        mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(devSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(otherUrl)))
                .andExpect(status().isCreated());

        SeededPlan mismatch = prepareRealGreenhousePlan("mismatch-" + UUID.randomUUID(), targetUrl);
        String raw = canonicalGreenhouseEvidence(mismatch.planId(), mismatch.applicationId(), "GH-SBX-DIFF1", null);
        mvc.perform(post("/api/v1/automation/plans/" + mismatch.planId() + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completionBody("GH-SBX-DIFF1",
                                List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX"), raw))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/applications/" + mismatch.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true));
        mvc.perform(get("/api/v1/applications/" + mismatch.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("UNVERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("UNVERIFIED"));
    }

    @Test
    void crossCandidateSandboxRegistrationCannotVerifySubmission() throws Exception {
        String isolatedUrl = "https://boards.greenhouse.io/robin-sandbox/jobs/8200684";
        // Cross-candidate isolation: otherSession registers isolatedUrl, devProfileId submits -> stays UNVERIFIED
        mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(otherSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(isolatedUrl)))
                .andExpect(status().isCreated());

        SeededPlan foreignSandboxPlan = prepareRealGreenhousePlan("cross-" + UUID.randomUUID(), isolatedUrl);
        String foreignRaw = canonicalGreenhouseEvidence(foreignSandboxPlan.planId(), foreignSandboxPlan.applicationId(), "GH-SBX-CROSS1", null);
        mvc.perform(post("/api/v1/automation/plans/" + foreignSandboxPlan.planId() + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completionBody("GH-SBX-CROSS1",
                                List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX"), foreignRaw))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/applications/" + foreignSandboxPlan.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true));
        mvc.perform(get("/api/v1/applications/" + foreignSandboxPlan.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("UNVERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("UNVERIFIED"));

        // Foreign candidate also cannot read devProfileId's receipt (404)
        mvc.perform(get("/api/v1/applications/" + foreignSandboxPlan.applicationId() + "/receipt").session(otherSession))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokingSandboxAfterCaptureInvalidatesTrustWhileTrackingUrlQueriesReconcileSafely() throws Exception {
        String baseSandboxUrl = "https://boards.greenhouse.io/robin-sandbox/jobs/8200690";
        String executedWithTrackingUrl = baseSandboxUrl + "?gh_src=linkedin&utm_medium=social";

        MvcResult regRes = mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(devSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(baseSandboxUrl)))
                .andExpect(status().isCreated())
                .andReturn();
        String regId = new ObjectMapper().readTree(regRes.getResponse().getContentAsString()).get("id").asText();

        SeededPlan seeded = prepareRealGreenhousePlan("tracking-revoke-" + UUID.randomUUID(), executedWithTrackingUrl);
        String raw = canonicalGreenhouseEvidence(seeded.planId(), seeded.applicationId(), "GH-SBX-TRK1", null);
        List<String> signals = List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX", "EMPLOYER_CONFIRMATION_ID_OBSERVED");

        mvc.perform(post("/api/v1/automation/plans/" + seeded.planId() + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completionBody("GH-SBX-TRK1", signals, raw))))
                .andExpect(status().isNoContent());

        // Tracking query params reconcile cleanly to the registered sandbox URL
        mvc.perform(get("/api/v1/applications/" + seeded.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(true));
        mvc.perform(get("/api/v1/applications/" + seeded.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("VERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("CONFIRMED"));

        // Post-capture revocation preserves immutable DB rows but revokes active trust on receipt + track
        mvc.perform(post("/api/v1/sandbox-registrations/" + regId + "/revoke")
                        .session(devSession)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        Integer receiptRows = jdbc.queryForObject(
                "select count(*) from submission_receipts where plan_id = ?", Integer.class, seeded.planId());
        assertThat(receiptRows).isEqualTo(1);

        mvc.perform(get("/api/v1/applications/" + seeded.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true));
        mvc.perform(get("/api/v1/applications/" + seeded.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("UNVERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("UNVERIFIED"));
    }

    @Test
    void concurrentCaptureAndRevokeNeverProduceActiveTrustForRevokedRegistration() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 4; attempt++) {
                final int trial = attempt;
                String baseSandboxUrl = "https://boards.greenhouse.io/robin-sandbox/jobs/" + (8200710 + trial);
                String postingUrl = baseSandboxUrl + "?utm_source=race_" + trial;
                MvcResult regRes = mvc.perform(post("/api/v1/sandbox-registrations")
                                .session(devSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                        """.formatted(baseSandboxUrl)))
                        .andExpect(status().isCreated())
                        .andReturn();
                String regId = new ObjectMapper().readTree(regRes.getResponse().getContentAsString()).get("id").asText();

                SeededPlan seeded = prepareRealGreenhousePlan("race-" + trial + "-" + UUID.randomUUID(), postingUrl);
                String raw = canonicalGreenhouseEvidence(seeded.planId(), seeded.applicationId(), "GH-RACE-" + trial, null);
                List<String> signals = List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX", "EMPLOYER_CONFIRMATION_ID_OBSERVED");

                var startGate = new java.util.concurrent.CountDownLatch(1);
                var doneGate = new java.util.concurrent.CountDownLatch(2);
                var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();

                executor.submit(() -> {
                    try {
                        startGate.await();
                        mvc.perform(post("/api/v1/automation/plans/" + seeded.planId() + "/complete")
                                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(completionBody("GH-RACE-" + trial, signals, raw))))
                                .andExpect(status().isNoContent());
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneGate.countDown();
                    }
                });

                executor.submit(() -> {
                    try {
                        startGate.await();
                        mvc.perform(post("/api/v1/sandbox-registrations/" + regId + "/revoke")
                                        .session(devSession)
                                        .with(csrf()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(false));
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneGate.countDown();
                    }
                });

                startGate.countDown();
                assertThat(doneGate.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(errors).isEmpty();

                Integer receiptRows = jdbc.queryForObject(
                        "select count(*) from submission_receipts where plan_id = ?", Integer.class, seeded.planId());
                assertThat(receiptRows).isBetween(0, 1);

                // In every interleaving, revoked sandbox trust must never yield confirmed=true or VERIFIED tracking.
                mvc.perform(get("/api/v1/applications/" + seeded.applicationId() + "/receipt").session(devSession))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                        .andExpect(jsonPath("$.reconciliation.required").value(true));
                mvc.perform(get("/api/v1/applications/" + seeded.applicationId() + "/track").session(devSession))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.basis").value("UNVERIFIED"))
                        .andExpect(jsonPath("$.submission.state").value("UNVERIFIED"));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void confirmedSubmissionAdvancesApplicationStartingFromReadyToApplyWithoutFailure() throws Exception {
        String postingUrl = "https://boards.greenhouse.io/robin-sandbox/jobs/8200799";
        mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(devSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(postingUrl)))
                .andExpect(status().isCreated());

        SeededPlan seeded = prepareRealGreenhousePlan("ready-to-apply-" + UUID.randomUUID(), postingUrl);
        jdbc.update("update applications set status = 'READY_TO_APPLY' where id = ?", seeded.applicationId());

        String raw = canonicalGreenhouseEvidence(seeded.planId(), seeded.applicationId(), "GH-READY-99", null);
        List<String> signals = List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX", "EMPLOYER_CONFIRMATION_ID_OBSERVED");

        mvc.perform(post("/api/v1/automation/plans/" + seeded.planId() + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completionBody("GH-READY-99", signals, raw))))
                .andExpect(status().isNoContent());

        String appStatus = jdbc.queryForObject(
                "select status from applications where id = ?", String.class, seeded.applicationId());
        assertThat(appStatus).isEqualTo("APPLICATION_SUBMITTED");
        mvc.perform(get("/api/v1/applications/" + seeded.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("VERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("CONFIRMED"));
    }

    private static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
