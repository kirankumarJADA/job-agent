package com.personal.jobagent.discovery.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.personal.jobagent.discovery.platform.FixtureHttp.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** /jobs/import-url: validation, SSRF safety, detection, preview and confirmed configuration. */
class BoardImportServiceTest {

    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final BoardDiscoveryService runner = mock(BoardDiscoveryService.class);
    private final PlatformRegistry registry = new PlatformRegistry(40);
    private FixtureHttp http;
    private BoardImportService service;

    @BeforeEach
    void setUp() {
        http = new FixtureHttp().resolve("internal.example.com", "10.0.0.8").resolve("meta.example.com", "169.254.169.254");
        service = new BoardImportService(db, registry, runner, http.guard(), http::client);
    }

    private Map<String, Object> importUrl(String url) {
        return service.importUrl(new BoardImportService.ImportRequest(url, null, null, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/careers", "https://127.0.0.1/jobs", "https://[::1]/", "file:///etc/passwd",
            "ftp://jobs.example.com/x", "https://user:pw@jobs.example.com/", "https://jobs.example.com:8080/",
            "https://internal.example.com/careers", "https://meta.example.com/latest/meta-data/", "javascript:alert(1)",
            "https://169.254.169.254/latest/meta-data/", "not a url"})
    void unsafeOrInvalidUrlsAreRejectedBeforeAnyFetch(String url) {
        assertThatThrownBy(() -> importUrl(url)).isInstanceOf(BoardImportService.ImportRejected.class)
                .satisfies(e -> assertThat(((BoardImportService.ImportRejected) e).status()).isEqualTo(400));
        assertThat(http.requests).isEmpty();
    }

    @Test
    void anOverlongUrlIsRejected() {
        assertThatThrownBy(() -> importUrl("https://jobs.example.com/" + "a".repeat(2100)))
                .isInstanceOf(BoardImportService.ImportRejected.class).hasMessageContaining("too long");
    }

    @Test
    void aSupportedBoardUrlIsResolvedWithoutFetchingAnything() {
        Map<String, Object> out = importUrl("https://jobs.lever.co/palantir/6ed76ce8-4156-4b60-b120-403538bd66cd");
        assertThat(out).containsEntry("status", "RESOLVED").containsEntry("board", "palantir");
        assertThat(((Map<?, ?>) out.get("platform")).get("kind")).isEqualTo("LEVER");
        assertThat(http.requests).isEmpty();
        verify(runner, never()).run(any(), any());
    }

    @Test
    void httpIsUpgradedToHttps() {
        Map<String, Object> out = importUrl("http://boards.greenhouse.io/stripe");
        assertThat(out.get("url")).isEqualTo("https://boards.greenhouse.io/stripe");
        assertThat(out).containsEntry("board", "stripe");
    }

    @Test
    void anUnsupportedPlatformIsNamedWithTheReasonNotFetched() {
        Map<String, Object> out = importUrl("https://sjobs.brassring.com/TGnewUI/Search/Home/Home?partnerid=25526&siteid=5032");
        assertThat(out).containsEntry("status", "UNSUPPORTED");
        assertThat(String.valueOf(out.get("message"))).contains("BrassRing", "blocked");
        assertThat(http.requests).isEmpty();
    }

    @Test
    void aCareersPageWithAnEmbeddedBoardIsDetectedWithOneGuardedFetch() {
        http.on("GET", "careers.acme.com", html("<html><body><iframe src=\"https://boards.greenhouse.io/embed/job_board?for=acme&amp;b=x\">"
                + "</iframe><a href=\"https://jobs.lever.co/other\">x</a></body></html>"));
        Map<String, Object> out = importUrl("https://careers.acme.com/open-roles");
        assertThat(out).containsEntry("status", "RESOLVED").containsEntry("board", "acme");
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void aPhenomSiteIsDetectedFromItsSignatureOnItsOwnDomain() {
        http.on("GET", "careers.bigco.com", html("<html><script>var phApp = phApp || {}; phApp.ddo = {};</script></html>"));
        Map<String, Object> out = importUrl("https://careers.bigco.com/us/en/search-results");
        assertThat(out).containsEntry("status", "RESOLVED").containsEntry("board", "careers.bigco.com/us/en");
    }

    @Test
    void aSniffRedirectToAPrivateAddressIsRefused() {
        http.on("GET", "careers.acme.com", redirect("https://internal.example.com/admin"));
        assertThatThrownBy(() -> importUrl("https://careers.acme.com/")).isInstanceOf(BoardImportService.ImportRejected.class);
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void anUnrecognisedPageAsksForThePlatformAndBoard() {
        http.on("GET", "plain.example.com", html("<html><body>We are hiring!</body></html>"));
        Map<String, Object> out = importUrl("https://plain.example.com/jobs");
        assertThat(out).containsEntry("status", "UNRECOGNIZED");
        assertThat((List<?>) out.get("platforms")).hasSize(registry.supportedKinds().size());
    }

    @Test
    void anExplicitPlatformAndBoardAreValidated() {
        Map<String, Object> ok = service.importUrl(new BoardImportService.ImportRequest(
                "https://careers.nvidia.com/", "Workday", "nvidia.wd5/NVIDIAExternalCareerSite", null, null, null));
        assertThat(ok).containsEntry("status", "RESOLVED");
        Map<String, Object> needs = service.importUrl(new BoardImportService.ImportRequest(
                "https://careers.nvidia.com/", "UKG Pro", null, null, null, null));
        assertThat(needs).containsEntry("status", "NEEDS_CONFIGURATION");
        assertThat(String.valueOf(needs.get("message"))).contains("host/tenant/boardGuid");
        assertThatThrownBy(() -> service.importUrl(new BoardImportService.ImportRequest(
                "https://careers.nvidia.com/", "Workday", "../../etc", null, null, null)))
                .isInstanceOf(BoardImportService.ImportRejected.class);
    }

    @Test
    void previewListsOnePageWithoutIngestingAnything() {
        http.on("GET", "api.gem.com", json(fixture("gem-posts.json")));
        Map<String, Object> out = service.importUrl(new BoardImportService.ImportRequest(
                "https://jobs.gem.com/gem", null, null, true, null, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> preview = (Map<String, Object>) out.get("preview");
        assertThat(preview).containsEntry("listed_on_first_page", 2);
        assertThat(String.valueOf(preview.get("sample_titles"))).contains("Software Engineer");
        verify(runner, never()).run(any(), any());
        verify(db, never()).update(contains("insert into job_sources"), any(Object[].class));
    }

    @Test
    void confirmingCreatesAScheduledDiscoveryOnlySourceAndRunsItOnce() {
        UUID created = UUID.randomUUID();
        when(db.queryForList(contains("from job_sources where kind = ?"), eq("GEM"), eq("gem")))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(Map.of("id", created, "enabled", true, "display_name", "Gem (Gem)")));
        when(runner.run(created, BoardDiscoveryService.Mode.IMPORT)).thenReturn(new BoardDiscoveryService.SourceRunReport(
                "c", created, "GEM", "ok", true, Map.of("created", 2), null, null, List.of(), List.of()));
        Map<String, Object> out = service.importUrl(new BoardImportService.ImportRequest(
                "https://jobs.gem.com/gem", null, null, null, true, "Gem"));
        assertThat(out).containsEntry("status", "CONFIGURED").containsEntry("source_id", created.toString());
        verify(db).update(contains("'DISCOVERY_ONLY'"), any(UUID.class), eq("GEM"), eq("gem"), eq("Gem (Gem)"), eq("0 0 */6 * * ?"));
        verify(runner).run(created, BoardDiscoveryService.Mode.IMPORT);
    }

    @Test
    void anExistingDisabledSourceIsNeverReEnabled() {
        UUID existing = UUID.randomUUID();
        when(db.queryForList(contains("from job_sources where kind = ?"), eq("GEM"), eq("gem")))
                .thenReturn(List.of(Map.of("id", existing, "enabled", false, "display_name", "Gem")));
        Map<String, Object> out = service.importUrl(new BoardImportService.ImportRequest(
                "https://jobs.gem.com/gem", null, null, null, true, null));
        assertThat(out).containsEntry("status", "SOURCE_DISABLED");
        verify(runner, never()).run(any(), any());
        verify(db, never()).update(anyString(), any(Object[].class));
    }
}
