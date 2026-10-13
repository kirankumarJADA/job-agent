package com.personal.jobagent.discovery.platform;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE checks against real public boards — separately labelled (tag "live")
 * and opt-in: {@code mvn verify -Dlive.discovery=true -Dit.test=PlatformLiveIT}.
 * Each case lists one page of a real tenant through the production
 * {@link SafeHttpClient} (SSRF guard, timeouts, budget) and, where the
 * platform needs it, fetches one detail page. A fixture test proves the
 * parser; only this test proves the endpoint is still there.
 *
 * <p>Tenants are the public boards used during Phase 8.2.5 validation. A
 * tenant that legitimately closes its board turns a case red; replace the
 * tenant rather than weakening the assertion.
 */
@Tag("live")
@EnabledIfSystemProperty(named = "live.discovery", matches = "true",
        disabledReason = "Live public-board checks run only with -Dlive.discovery=true")
class PlatformLiveIT {

    private final PlatformRegistry registry = new PlatformRegistry(3);

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "WORKDAY, nvidia.wd5/NVIDIAExternalCareerSite",
            "GREENHOUSE, stripe",
            "LEVER, palantir",
            "ASHBY, ramp",
            "RIPPLING, rippling",
            "ICIMS, us-erac.icims.com",
            "WORKABLE, huggingface",
            "JAZZHR, labelmaster",
            "JOBVITE, egnyte",
            "BREEZYHR, breezy",
            "ORACLE_CLOUD, eeho.fa.us2.oraclecloud.com/CX_45001",
            "SMARTRECRUITERS, BoschGroup",
            "PAYLOCITY, 3876b9cb-3f4c-41a0-a88e-91ef0efab17e",
            "ULTIPRO, recruiting2.ultipro.com/HEL1006HELI/73d20f56-33cd-4859-a6e3-7c858a9a8241",
            "GEM, gem",
            "ZOHO_RECRUIT, sehyog.zohorecruit.in",
            "JOIN, join",
            "PHENOM, careers.phenom.com/us/en",
            "PINPOINT, workwithus.pinpointhq.com",
            "RECRUITEE, vandebron",
            "TEAMTAILOR, career.teamtailor.com",
            "TSENTA, jobs"
    })
    void liveBoardAnswersAndParses(String kind, String board) {
        PlatformKind platform = PlatformKind.valueOf(kind);
        BoardAdapter adapter = registry.adapter(platform).orElseThrow();
        String identifier = platform == PlatformKind.PINPOINT ? board.replace(".pinpointhq.com", "") : board;
        BoardAdapter.BoardRef ref = adapter.parseIdentifier(identifier);
        SafeHttpClient http = SafeHttpClient.live(SafeHttpClient.Settings.defaults()
                .withMaxRequests(8).withMinInterval(platform == PlatformKind.TSENTA ? 1500 : 300)
                .withTimeout(Duration.ofSeconds(25)));
        BoardAdapter.CrawlContext context = new BoardAdapter.CrawlContext(http, 1, Instant.now(), null);

        BoardAdapter.ListingResult listing = adapter.list(ref, context);

        assertThat(listing.failure() == null || listing.failure() == FailureCategory.BUDGET_EXHAUSTED)
                .as(kind + " listing failed: " + listing.failure() + " " + listing.failureDetail()).isTrue();
        assertThat(listing.entries()).as(kind + " returned no postings").isNotEmpty();
        // Check up to three postings: a real posting may legitimately have no description (Jobvite's
        // oneco board has such postings), so each must parse with a title and a genuine URL, and at least
        // one must carry a description to prove description parsing works on the live markup.
        NormalizedJob job = null;
        int withDescription = 0;
        for (BoardAdapter.ListingEntry entry : listing.entries().subList(0, Math.min(3, listing.entries().size()))) {
            NormalizedJob parsed = entry.needsDetail() ? adapter.detail(ref, entry, context) : entry.job();
            assertThat(parsed.externalId()).isNotBlank();
            assertThat(parsed.title()).as(kind + " title").isNotBlank();
            if (platform == PlatformKind.TSENTA) {
                // The employer URL may legitimately be absent; it must never be a Tsenta URL.
                assertThat(parsed.applicationUrl() == null || !JobIdentity.isAggregatorUrl(parsed.applicationUrl())).isTrue();
            } else {
                assertThat(parsed.applicationUrl()).as(kind + " application URL").startsWith("https://");
            }
            if (parsed.descriptionText() != null && !parsed.descriptionText().isBlank()) withDescription++;
            if (job == null) job = parsed;
        }
        if (platform != PlatformKind.TSENTA) {
            assertThat(withDescription).as(kind + " description parsed for at least one posting").isPositive();
        }
        System.out.println("LIVE " + Map.of("kind", kind, "listed", listing.entries().size(), "complete", listing.complete(),
                "requests", http.requestsMade(), "sampleTitle", job.title()));
    }
}
