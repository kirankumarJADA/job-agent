package com.personal.jobagent.discovery.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Automated coverage audit for the 30 platforms + Tsenta. It fails when:
 * the manifest drifts from Tsenta's published list; a platform is claimed
 * LIVE_VALIDATED without a registered adapter, real fixtures and tests (or the
 * reverse); an unverified/blocked platform silently gains or loses an adapter;
 * or the database would reject a manifest kind.
 */
class PlatformCoverageAuditTest {

    /** Verbatim order and spelling from https://tsenta.com/ai-disclosure ("Supported Job Platforms"), checked 2026-10-10. */
    static final List<String> TSENTA_LIST = List.of("Workday", "Greenhouse", "Lever", "Ashby", "Rippling", "iCIMS", "BambooHR",
            "Workable", "JazzHR", "Jobvite", "BreezyHR", "Oracle Cloud", "SmartRecruiters", "Paylocity", "UltiPro", "ADP",
            "Dover", "Gem", "Zoho Recruit", "BrassRing", "CareerPlug", "Dayforce", "Hireology", "Join", "Phenom", "Pinpoint",
            "Polymer", "Recruitee", "SAP SuccessFactors", "Teamtailor");

    /** Real captured fixtures backing each live adapter. */
    static final Map<PlatformKind, List<String>> FIXTURES = Map.ofEntries(
            Map.entry(PlatformKind.WORKDAY, List.of("workday-list.json", "workday-list-page2.json", "workday-detail.json")),
            Map.entry(PlatformKind.GREENHOUSE, List.of("../greenhouse-board-fixture.json")),
            Map.entry(PlatformKind.LEVER, List.of("lever-postings.json")),
            Map.entry(PlatformKind.ASHBY, List.of("../ashby-linear-job-board-fixture.json")),
            Map.entry(PlatformKind.RIPPLING, List.of("rippling-list.json", "rippling-detail.json")),
            Map.entry(PlatformKind.ICIMS, List.of("icims-list.html", "icims-list-page2.html", "icims-detail.html")),
            Map.entry(PlatformKind.WORKABLE, List.of("workable-widget.json")),
            Map.entry(PlatformKind.JAZZHR, List.of("jazzhr-feed.xml")),
            Map.entry(PlatformKind.JOBVITE, List.of("jobvite-list.html", "jobvite-detail.html")),
            Map.entry(PlatformKind.BREEZYHR, List.of("breezy-list.json", "breezy-detail.html")),
            Map.entry(PlatformKind.ORACLE_CLOUD, List.of("oracle-list.json", "oracle-detail.json")),
            Map.entry(PlatformKind.SMARTRECRUITERS, List.of("smartrecruiters-list.json", "smartrecruiters-detail.json")),
            Map.entry(PlatformKind.PAYLOCITY, List.of("paylocity-list.html", "paylocity-detail.html")),
            Map.entry(PlatformKind.ULTIPRO, List.of("ultipro-list.json", "ultipro-detail.html")),
            Map.entry(PlatformKind.GEM, List.of("gem-posts.json")),
            Map.entry(PlatformKind.ZOHO_RECRUIT, List.of("zoho-openings.json")),
            Map.entry(PlatformKind.JOIN, List.of("join-list.html", "join-detail.html")),
            Map.entry(PlatformKind.PHENOM, List.of("phenom-widgets.json", "phenom-detail.html")),
            Map.entry(PlatformKind.PINPOINT, List.of("pinpoint-postings.json")),
            Map.entry(PlatformKind.RECRUITEE, List.of("recruitee-offers.json")),
            Map.entry(PlatformKind.TEAMTAILOR, List.of("teamtailor.rss")),
            Map.entry(PlatformKind.TSENTA, List.of("tsenta-list-page2.html", "tsenta-list-page3.html", "tsenta-detail-zoho.html",
                    "tsenta-detail-unsupported.html", "tsenta-robots.txt")));

    private final PlatformRegistry registry = new PlatformRegistry(40);

    @Test
    void theManifestIsExactlyTsentasThirtyPlatformsPlusTsentaAsASeparateSource() {
        assertThat(PlatformKind.atsPlatforms()).extracting(PlatformKind::displayName).containsExactlyElementsOf(TSENTA_LIST);
        assertThat(PlatformKind.values()).hasSize(31);
        assertThat(PlatformKind.TSENTA.isAggregator()).isTrue();
        assertThat(PlatformKind.atsPlatforms()).noneMatch(PlatformKind::isAggregator);
    }

    @Test
    void everyNameKindAndAliasResolvesToExactlyOnePlatform() {
        Map<String, PlatformKind> seen = new HashMap<>();
        for (PlatformKind kind : PlatformKind.values()) {
            assertThat(PlatformKind.lookup(kind.name())).contains(kind);
            assertThat(PlatformKind.lookup(kind.displayName())).contains(kind);
            for (String alias : kind.aliases()) {
                assertThat(PlatformKind.lookup(alias)).as(alias).contains(kind);
                PlatformKind previous = seen.put(alias.toLowerCase().replaceAll("[^a-z0-9]", ""), kind);
                assertThat(previous == null || previous == kind).as("alias collision: " + alias).isTrue();
            }
            assertThat(kind.configHint()).isNotBlank();
        }
        assertThat(PlatformKind.lookup("UKG Pro")).contains(PlatformKind.ULTIPRO);
        assertThat(PlatformKind.lookup("Kenexa")).contains(PlatformKind.BRASSRING);
        assertThat(PlatformKind.lookup("SuccessFactors")).contains(PlatformKind.SAP_SUCCESSFACTORS);
        assertThat(PlatformKind.lookup("nonsense")).isEmpty();
    }

    @Test
    void liveValidatedPlatformsAndOnlyThoseHaveARegisteredAdapter() {
        for (PlatformKind kind : PlatformKind.values()) {
            boolean adapter = registry.adapter(kind).isPresent();
            assertThat(adapter).as(kind + " adapter presence must match readiness " + kind.readiness())
                    .isEqualTo(kind.readiness() == PlatformKind.Readiness.LIVE_VALIDATED);
            registry.adapter(kind).ifPresent(a -> assertThat(a.kind()).isEqualTo(kind));
        }
    }

    @Test
    void everyLiveAdapterIsBackedByRealFixturesThatExist() throws IOException {
        for (PlatformKind kind : registry.supportedKinds()) {
            assertThat(FIXTURES).as(kind + " has no fixtures listed").containsKey(kind);
            for (String name : FIXTURES.get(kind)) {
                String path = name.startsWith("../") ? "/discovery/" + name.substring(3) : "/discovery/platforms/" + name;
                try (InputStream in = getClass().getResourceAsStream(path)) {
                    assertThat(in).as("fixture " + name).isNotNull();
                    assertThat(in.readAllBytes().length).as("fixture " + name).isGreaterThan(100);
                }
            }
        }
    }

    @Test
    void everyLiveAdapterIsExercisedByAFixtureTest() throws IOException {
        Path tests = Path.of("src/test/java/com/personal/jobagent/discovery");
        if (!Files.isDirectory(tests)) return; // only meaningful from the module directory (Maven)
        StringBuilder corpus = new StringBuilder();
        try (Stream<Path> files = Files.walk(tests)) {
            // The audit itself names every kind, so it must not count as coverage.
            for (Path p : files.filter(f -> f.toString().endsWith("Test.java"))
                    .filter(f -> !f.getFileName().toString().equals("PlatformCoverageAuditTest.java")).toList()) {
                corpus.append(Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        for (PlatformKind kind : registry.supportedKinds()) {
            String adapterClass = registry.adapter(kind).orElseThrow().getClass().getSimpleName();
            boolean exercised = corpus.indexOf("new " + adapterClass + "(") >= 0
                    || corpus.indexOf("." + adapterClass + "(") >= 0
                    || corpus.indexOf("PlatformKind." + kind.name()) >= 0;
            assertThat(exercised).as(kind + " (" + adapterClass + ") is not exercised by any test").isTrue();
        }
    }

    @Test
    void unverifiedAndBlockedPlatformsAreStillRecognisedSoImportsCanExplainWhy() {
        Map<PlatformKind, String> samples = Map.of(
                PlatformKind.BAMBOOHR, "https://acme.bamboohr.com/careers/12",
                PlatformKind.ADP, "https://workforcenow.adp.com/mascsr/default/mdf/recruitment/recruitment.html?cid=x",
                PlatformKind.DOVER, "https://app.dover.com/jobs/careeros",
                PlatformKind.BRASSRING, "https://sjobs.brassring.com/TGnewUI/Search/Home/Home?partnerid=25526&siteid=5032",
                PlatformKind.CAREERPLUG, "https://acme.careerplug.com/jobs",
                PlatformKind.DAYFORCE, "https://jobs.dayforcehcm.com/en-US/acme/CANDIDATEPORTAL",
                PlatformKind.HIREOLOGY, "https://careers.hireology.com/acme",
                PlatformKind.POLYMER, "https://jobs.polymer.co/outcome",
                PlatformKind.SAP_SUCCESSFACTORS, "https://career4.successfactors.com/career?company=C0004326754P");
        for (PlatformKind kind : PlatformKind.values()) {
            if (kind.readiness() == PlatformKind.Readiness.LIVE_VALIDATED) continue;
            assertThat(samples).as(kind + " needs a recognition sample").containsKey(kind);
            var recognition = registry.recognize(URI.create(samples.get(kind)));
            assertThat(recognition).as(kind.name()).isPresent();
            assertThat(recognition.get().kind()).isEqualTo(kind);
            assertThat(recognition.get().fetchable()).isFalse();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite, WORKDAY",
            "https://boards.greenhouse.io/stripe, GREENHOUSE",
            "https://jobs.lever.co/palantir, LEVER",
            "https://jobs.ashbyhq.com/ramp, ASHBY",
            "https://ats.rippling.com/rippling/jobs, RIPPLING",
            "https://us-erac.icims.com/jobs/search, ICIMS",
            "https://apply.workable.com/huggingface/, WORKABLE",
            "https://labelmaster.applytojob.com/apply/jobs/, JAZZHR",
            "https://jobs.jobvite.com/oneco/jobs, JOBVITE",
            "https://breezy.breezy.hr/, BREEZYHR",
            "https://eeho.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_45001/requisitions, ORACLE_CLOUD",
            "https://jobs.smartrecruiters.com/BoschGroup, SMARTRECRUITERS",
            "https://recruiting.paylocity.com/recruiting/jobs/All/3876b9cb-3f4c-41a0-a88e-91ef0efab17e, PAYLOCITY",
            "https://recruiting2.ultipro.com/HEL1006HELI/JobBoard/73d20f56-33cd-4859-a6e3-7c858a9a8241/, ULTIPRO",
            "https://jobs.gem.com/gem, GEM",
            "https://sehyog.zohorecruit.in/jobs/Careers, ZOHO_RECRUIT",
            "https://join.com/companies/join, JOIN",
            "https://careers.phenom.com/us/en/search-results, PHENOM",
            "https://workwithus.pinpointhq.com/, PINPOINT",
            "https://vandebron.recruitee.com/, RECRUITEE",
            "https://career.teamtailor.com/jobs, TEAMTAILOR",
            "https://tsenta.com/jobs, TSENTA"
    })
    void everyLivePlatformIsRecognisedFromItsPublicUrl(String url, String kind) {
        var recognition = registry.recognize(URI.create(url));
        assertThat(recognition).isPresent();
        assertThat(recognition.get().kind().name()).isEqualTo(kind);
        assertThat(recognition.get().fetchable()).isTrue();
    }

    @Test
    void theDatabaseAcceptsEveryManifestKind() throws IOException {
        String migration;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V037__platform_discovery_expansion.sql")) {
            assertThat(in).isNotNull();
            migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> missing = new ArrayList<>();
        for (PlatformKind kind : PlatformKind.values()) {
            if (!migration.contains("'" + kind.name() + "'")) missing.add(kind.name());
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void coverageStatusIsDerivedHonestly() {
        assertThat(PlatformCoverageService.status(PlatformKind.BRASSRING, false, 3, 0))
                .isEqualTo(PlatformCoverageService.CoverageStatus.BLOCKED);
        assertThat(PlatformCoverageService.status(PlatformKind.DOVER, false, 1, 0))
                .isEqualTo(PlatformCoverageService.CoverageStatus.UNVERIFIED);
        assertThat(PlatformCoverageService.status(PlatformKind.WORKDAY, true, 0, 0))
                .isEqualTo(PlatformCoverageService.CoverageStatus.NEEDS_BOARD_CONFIGURATION);
        assertThat(PlatformCoverageService.status(PlatformKind.WORKDAY, true, 2, 2))
                .isEqualTo(PlatformCoverageService.CoverageStatus.TEMPORARILY_UNAVAILABLE);
        assertThat(PlatformCoverageService.status(PlatformKind.WORKDAY, true, 2, 1))
                .isEqualTo(PlatformCoverageService.CoverageStatus.SUPPORTED);
        // An adapter is never SUPPORTED on readiness alone.
        assertThat(PlatformCoverageService.status(PlatformKind.LEVER, false, 2, 0))
                .isEqualTo(PlatformCoverageService.CoverageStatus.UNVERIFIED);
    }

    @Test
    void currentImplementationCounts() {
        long live = PlatformKind.atsPlatforms().stream().filter(k -> k.readiness() == PlatformKind.Readiness.LIVE_VALIDATED).count();
        long unverified = PlatformKind.atsPlatforms().stream().filter(k -> k.readiness() == PlatformKind.Readiness.UNVERIFIED).count();
        long blocked = PlatformKind.atsPlatforms().stream().filter(k -> k.readiness() == PlatformKind.Readiness.BLOCKED).count();
        // Documented in docs/PHASE8_FIND_WORKFLOW.md; change both together.
        assertThat(live).isEqualTo(21);
        assertThat(unverified).isEqualTo(8);
        assertThat(blocked).isEqualTo(1);
    }
}
