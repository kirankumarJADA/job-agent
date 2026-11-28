package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.SafeUrls;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Stage 3F backend hardening: artifacts (Workstreams D/F) and URL handling (Workstream E). */
class Stage3FBackendHardeningTest {

    private final UUID planId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private ExecutionPackageService serviceWith(JdbcTemplate db) {
        when(db.queryForObject(contains("select job_id from applications"), eq(UUID.class), eq(applicationId), eq(profileId)))
                .thenReturn(jobId);
        return new ExecutionPackageService(db, null, null, null, null, new ObjectMapper(), null);
    }

    @Test
    void pdfCoverLetterArtifactIsServedAsPdfAndReverifiedAgainstItsRecordedChecksum() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        byte[] pdf = "%PDF-1.7\nletter".getBytes(StandardCharsets.UTF_8);
        when(db.queryForList(contains("from cover_letters c join files f"), eq(versionId), eq(profileId), eq(jobId), eq(applicationId)))
                .thenReturn(List.of(Map.of("content", pdf, "sha256", sha(pdf))));

        var bytes = serviceWith(db).artifactBytes(planId, profileId, applicationId, jobId, "cover-letter-pdf", versionId);

        assertThat(bytes.contentType()).isEqualTo("application/pdf");
        assertThat(bytes.fileName()).isEqualTo("cover-letter-" + versionId + ".pdf");
        assertThat(bytes.sha256()).isEqualTo(sha(pdf));
    }

    @Test
    void tamperedPdfCoverLetterIsRefused() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        byte[] pdf = "%PDF-1.7\nletter".getBytes(StandardCharsets.UTF_8);
        when(db.queryForList(contains("from cover_letters c join files f"), eq(versionId), eq(profileId), eq(jobId), eq(applicationId)))
                .thenReturn(List.of(Map.of("content", pdf, "sha256", "0".repeat(64))));

        assertThatThrownBy(() -> serviceWith(db).artifactBytes(planId, profileId, applicationId, jobId, "cover-letter-pdf", versionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COVER_LETTER_PDF_CHECKSUM_MISMATCH");
    }

    @Test
    void artifactsOverTheTenMebibyteCapAreRefusedServerSide() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        byte[] huge = new byte[ExecutionPackageService.MAX_ARTIFACT_BYTES + 1];
        huge[0] = '%';
        when(db.queryForList(contains("join cv_versions v"), any(Object[].class)))
                .thenReturn(List.of(Map.of("content", huge, "sha256", sha(huge), "content_type", "application/pdf")));

        assertThatThrownBy(() -> serviceWith(db).artifactBytes(planId, profileId, applicationId, jobId, "cv", versionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARTIFACT_TOO_LARGE");
    }

    @Test
    void dedicatedAtsDetectionUsesTheParsedHostOnALabelBoundary() {
        assertThat(UniversalBrowserPlanService.isDedicatedAtsUrl("https://boards.greenhouse.io/acme/jobs/1")).isTrue();
        assertThat(UniversalBrowserPlanService.isDedicatedAtsUrl("https://job-boards.greenhouse.io/acme/jobs/1")).isTrue();
        assertThat(UniversalBrowserPlanService.isDedicatedAtsUrl("https://acme.wd5.myworkdayjobs.com/en-US/x")).isTrue();
        assertThat(UniversalBrowserPlanService.isDedicatedAtsUrl("https://JOBS.LEVER.CO./acme/1")).isTrue();
        // Substring tricks no longer count as a dedicated ATS host.
        assertThat(UniversalBrowserPlanService.isDedicatedAtsUrl("https://careers.acme.example/?ref=greenhouse.io")).isFalse();
        assertThat(UniversalBrowserPlanService.isDedicatedAtsUrl("https://greenhouse.io.evil.example/apply")).isFalse();
        assertThat(UniversalBrowserPlanService.isDedicatedAtsUrl("https://notmyworkdayjobs.com/apply")).isFalse();
        assertThat(UniversalBrowserPlanService.isDedicatedAtsUrl("https://careers.acme.example/jobs.lever.co/apply")).isFalse();
        assertThat(UniversalBrowserPlanService.isDedicatedAtsUrl("not a url")).isFalse();
    }

    @Test
    void handoffLinksMustStayOnThePlansEmployerHostOrItsOwnAtsPlatform() {
        assertThat(com.personal.jobagent.automation.handoff.AtsHandoffService.sameEmployerHost(
                "careers.acme.example", "careers.acme.example")).isTrue();
        assertThat(com.personal.jobagent.automation.handoff.AtsHandoffService.sameEmployerHost(
                "job-boards.greenhouse.io", "boards.greenhouse.io")).isTrue();
        assertThat(com.personal.jobagent.automation.handoff.AtsHandoffService.sameEmployerHost(
                "login.careers.acme.example", "careers.acme.example")).isFalse();
        assertThat(com.personal.jobagent.automation.handoff.AtsHandoffService.sameEmployerHost(
                "greenhouse.io.evil.example", "boards.greenhouse.io")).isFalse();
        assertThat(com.personal.jobagent.automation.handoff.AtsHandoffService.sameEmployerHost(
                "acme-careers.login-check.net", "careers.acme.example")).isFalse();
    }

    @Test
    void urlsWrittenToLogsAndAuditRowsNeverCarryQueriesFragmentsOrUserinfo() {
        assertThat(SafeUrls.redactForLog("https://user:pw@Careers.Acme.example:8443/apply/42?token=s3cr3t&email=a@b.c#frag"))
                .isEqualTo("https://careers.acme.example:8443/apply/42");
        assertThat(SafeUrls.redactForLog("https://careers.acme.example/apply?session=abc")).isEqualTo("https://careers.acme.example/apply");
        assertThat(SafeUrls.redactForLog("javascript:alert(1)")).isEqualTo("[unparseable-url]");
        assertThat(SafeUrls.redactForLog(null)).isEmpty();
        assertThat(SafeUrls.canonicalHost("https://Careers.Acme.EXAMPLE./x")).contains("careers.acme.example");
        assertThat(SafeUrls.hostIsOrIsSubdomainOf("a.greenhouse.io", "greenhouse.io")).isTrue();
        assertThat(SafeUrls.hostIsOrIsSubdomainOf("evilgreenhouse.io", "greenhouse.io")).isFalse();
    }
}
