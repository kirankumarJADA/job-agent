package com.personal.jobagent.discovery.platform;

import com.personal.jobagent.discovery.platform.BoardAdapter.BoardRef;
import com.personal.jobagent.discovery.platform.BoardAdapter.ListingEntry;
import com.personal.jobagent.discovery.platform.BoardAdapter.ListingResult;
import com.personal.jobagent.discovery.platform.adapters.DetailFetchAdapters;
import com.personal.jobagent.discovery.platform.adapters.LeverAdapter;
import com.personal.jobagent.discovery.platform.adapters.MarkupAdapters;
import com.personal.jobagent.discovery.platform.adapters.RipplingAdapter;
import com.personal.jobagent.discovery.platform.adapters.SinglePayloadAdapters;
import com.personal.jobagent.discovery.platform.adapters.WorkdayAdapter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.personal.jobagent.discovery.platform.FixtureHttp.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixture tests for every live-validated board adapter. Every fixture under
 * {@code discovery/platforms/} is a trimmed copy of a response fetched from a
 * real public board on 2026-10-10 (tenant named in each adapter's Javadoc).
 * These prove the parsers against real schemas; they do NOT prove the live
 * endpoints still answer — that is {@code PlatformLiveIT} (tag "live").
 */
class PlatformAdapterFixtureTest {

    private static ListingEntry first(ListingResult r) {
        return r.entries().get(0);
    }

    private static NormalizedJob detail(BoardAdapter a, BoardRef b, ListingEntry e, FixtureHttp f) {
        return a.detail(b, e, f.context());
    }

    // ==========================================================================================
    @Nested
    class Workday {
        final WorkdayAdapter a = new WorkdayAdapter();
        final BoardRef board = a.parseIdentifier("nvidia.wd5/NVIDIAExternalCareerSite");

        @Test
        void recognisesCareerSiteAndCxsUrls() {
            assertThat(a.recognize(URI.create("https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/job/x_JR1"))
                    .map(BoardRef::identifier)).contains("nvidia.wd5/NVIDIAExternalCareerSite");
            assertThat(a.recognize(URI.create("https://nvidia.wd5.myworkdayjobs.com/wday/cxs/nvidia/NVIDIAExternalCareerSite/jobs"))
                    .map(BoardRef::identifier)).contains("nvidia.wd5/NVIDIAExternalCareerSite");
            assertThat(a.recognize(URI.create("https://boards.greenhouse.io/acme"))).isEmpty();
            assertThat(a.recognize(URI.create("https://evil.example.com/nvidia.wd5.myworkdayjobs.com/x"))).isEmpty();
        }

        @Test
        void missingOrMalformedConfigurationIsAClearError() {
            assertThatThrownBy(() -> a.parseIdentifier("nvidia")).isInstanceOf(BoardConfigException.class)
                    .hasMessageContaining("tenant.wdN/site");
            assertThatThrownBy(() -> a.parseIdentifier(null)).isInstanceOf(BoardConfigException.class);
            assertThatThrownBy(() -> a.parseIdentifier("../../etc.wd5/x")).isInstanceOf(BoardConfigException.class);
        }

        @Test
        void paginatesUsingTheFirstPageTotalAndFetchesDetails() {
            // Page 1 says total=25; page 2 (real capture) says total=0, as Workday does
            // for every page after the first — the first total must be kept.
            FixtureHttp f = new FixtureHttp()
                    .sequence("POST", "/jobs", json(fixture("workday-list.json").replace("\"total\": 2000", "\"total\": 25")),
                            json(fixture("workday-list-page2.json")))
                    .on("GET", "/wday/cxs/", json(fixture("workday-detail.json")));
            ListingResult r = a.list(board, f.context());
            assertThat(r.complete()).isTrue();
            assertThat(f.requests).hasSize(2);
            assertThat(r.entries()).hasSize(5).extracting(ListingEntry::externalId)
                    .contains("/job/US-CA-Santa-Clara/Senior-HPC-Storage-Engineer_JR2014997",
                            "/job/US-WA-Remote/Senior-Software-Engineer---Distributed-Systems-Engineer--EDA-Infrastructure_JR2024825-1");
            assertThat(f.requests.get(1).body()).contains("\"offset\":20");
            NormalizedJob job = detail(a, board, first(r), f);
            assertThat(job.title()).isEqualTo("Senior HPC Storage Engineer");
            assertThat(job.locationRaw()).isEqualTo("US, CA, Santa Clara; US, TX, Austin");
            assertThat(job.requisitionId()).isEqualTo("JR2014997");
            assertThat(job.employmentType()).isEqualTo("Full time");
            assertThat(job.applicationUrl()).isEqualTo(
                    "https://nvidia.wd5.myworkdayjobs.com/NVIDIAExternalCareerSite/job/US-CA-Santa-Clara/Senior-HPC-Storage-Engineer_JR2014997");
            assertThat(job.descriptionText()).startsWith("NVIDIA has been transforming").doesNotContain("<p>");
            // "Posted Today" is relative and is never turned into a date.
            assertThat(job.postedAt()).isNull();
            assertThat(job.salaryCurrency()).isNull();
        }

        @Test
        void endsOnTheTotalReportedByThePlatform() {
            String one = "{\"total\":1,\"jobPostings\":[{\"title\":\"Only\",\"externalPath\":\"/job/A/Only_R1\",\"bulletFields\":[\"R1\"]}]}";
            FixtureHttp f = new FixtureHttp().on("POST", "/jobs", json(one));
            ListingResult r = a.list(board, f.context());
            assertThat(r.complete()).isTrue();
            assertThat(r.entries()).hasSize(1);
            assertThat(f.requests).hasSize(1);
        }

        @Test
        void aPageCapBeforeTheEndIsPartialNeverComplete() {
            FixtureHttp f = new FixtureHttp().on("POST", "/jobs", json(fixture("workday-list.json")));
            ListingResult r = a.list(board, f.context(1));
            assertThat(r.complete()).isFalse();
            assertThat(r.failure()).isEqualTo(FailureCategory.BUDGET_EXHAUSTED);
            assertThat(r.entries()).hasSize(3);
        }

        @Test
        void anEmptyBoardIsCompleteAndEmpty() {
            FixtureHttp f = new FixtureHttp().on("POST", "/jobs", json("{\"total\":0,\"jobPostings\":[]}"));
            ListingResult r = a.list(board, f.context());
            assertThat(r.complete()).isTrue();
            assertThat(r.entries()).isEmpty();
        }

        @Test
        void malformedTimeoutRateLimitAndNotFoundAreCategorised() {
            assertThat(a.list(board, new FixtureHttp().on("POST", "/jobs", json("{not json")).context()).failure())
                    .isEqualTo(FailureCategory.MALFORMED_RESPONSE);
            assertThat(a.list(board, new FixtureHttp().on("POST", "/jobs", json("{\"total\":3}")).context()).failure())
                    .isEqualTo(FailureCategory.MALFORMED_RESPONSE);
            assertThat(a.list(board, new FixtureHttp().on("POST", "/jobs", TIMEOUT).context()).failure())
                    .isEqualTo(FailureCategory.TIMEOUT);
            assertThat(a.list(board, new FixtureHttp().on("POST", "/jobs", status(429)).context()).failure())
                    .isEqualTo(FailureCategory.RATE_LIMITED);
            assertThat(a.list(board, new FixtureHttp().on("POST", "/jobs", status(404)).context()).failure())
                    .isEqualTo(FailureCategory.NOT_FOUND);
        }

        @Test
        void aFailureAfterSomePagesKeepsThosePagesButIsPartial() {
            FixtureHttp f = new FixtureHttp().sequence("POST", "/jobs", json(fixture("workday-list.json")), status(503));
            ListingResult r = a.list(board, f.context());
            assertThat(r.complete()).isFalse();
            assertThat(r.failure()).isEqualTo(FailureCategory.UPSTREAM_ERROR);
            assertThat(r.entries()).hasSize(3);
        }
    }

    // ==========================================================================================
    @Nested
    class Lever {
        final LeverAdapter a = new LeverAdapter();

        @Test
        void recognisesGlobalAndEuBoards() {
            assertThat(a.recognize(URI.create("https://jobs.lever.co/palantir/6ed76ce8-4156-4b60-b120-403538bd66cd"))
                    .map(BoardRef::identifier)).contains("palantir");
            assertThat(a.recognize(URI.create("https://jobs.eu.lever.co/acme")).map(BoardRef::identifier)).contains("eu:acme");
            assertThat(a.parseIdentifier("eu:acme").param("api")).isEqualTo("https://api.eu.lever.co");
            assertThatThrownBy(() -> a.parseIdentifier("bad slug!")).isInstanceOf(BoardConfigException.class);
        }

        @Test
        void parsesPostingsCompletelyFromTheListing() {
            FixtureHttp f = new FixtureHttp().on("GET", "api.lever.co", json(fixture("lever-postings.json")));
            ListingResult r = a.list(a.parseIdentifier("palantir"), f.context());
            assertThat(r.complete()).isTrue();
            assertThat(r.entries()).allMatch(e -> !e.needsDetail());
            NormalizedJob j = first(r).job();
            assertThat(j.externalId()).isEqualTo("6ed76ce8-4156-4b60-b120-403538bd66cd");
            assertThat(j.title()).isEqualTo("Administrative Business Partner");
            assertThat(j.remoteType()).isEqualTo("HYBRID");
            assertThat(j.employmentType()).isEqualTo("Full-time");
            assertThat(j.postedAt()).isEqualTo(Instant.ofEpochMilli(1786469891368L));
            assertThat(j.applicationUrl()).endsWith("/apply");
            assertThat(j.sourceUrl()).isEqualTo("https://jobs.lever.co/palantir/6ed76ce8-4156-4b60-b120-403538bd66cd");
            assertThat(j.descriptionText()).contains("Palantir builds");
            assertThat(f.requests.get(0).uri().toString()).contains("skip=0&limit=100");
        }

        @Test
        void aFullPageTriggersTheNextPage() {
            StringBuilder page = new StringBuilder("[");
            for (int i = 0; i < 100; i++) {
                page.append(i == 0 ? "" : ",").append("{\"id\":\"id-").append(i).append("\",\"text\":\"Role ").append(i).append("\"}");
            }
            page.append("]");
            FixtureHttp f = new FixtureHttp().sequence("GET", "api.lever.co", json(page.toString()), json("[]"));
            ListingResult r = a.list(a.parseIdentifier("acme"), f.context());
            assertThat(r.complete()).isTrue();
            assertThat(r.entries()).hasSize(100);
            assertThat(f.requests.get(1).uri().toString()).contains("skip=100");
        }

        @Test
        void unknownSiteIsNotFound() {
            assertThat(a.list(a.parseIdentifier("nobody"), new FixtureHttp().on("GET", "api.lever.co", status(404)).context()).failure())
                    .isEqualTo(FailureCategory.NOT_FOUND);
        }
    }

    // ==========================================================================================
    @Nested
    class Rippling {
        final RipplingAdapter a = new RipplingAdapter();

        @Test
        void listsThenCompletesFromTheDetailEndpoint() {
            FixtureHttp f = new FixtureHttp().on("GET", "/jobs/", json(fixture("rippling-detail.json")))
                    .on("GET", "/rippling/jobs", json(fixture("rippling-list.json")));
            BoardRef b = a.parseIdentifier("rippling");
            ListingResult r = a.list(b, f.context());
            assertThat(r.complete()).isTrue();
            assertThat(r.entries()).hasSize(3).allMatch(ListingEntry::needsDetail);
            NormalizedJob j = detail(a, b, first(r), f);
            assertThat(j.title()).isEqualTo("Account Executive, Broker Channel (Arkansas)");
            assertThat(j.employerName()).isEqualTo("Rippling");
            assertThat(j.employmentType()).isEqualTo("Salaried, full-time");
            assertThat(j.postedAt()).isEqualTo(Instant.parse("2026-10-06T23:36:07.947Z"));
            assertThat(j.applicationUrl()).isEqualTo("https://ats.rippling.com/rippling/jobs/ef0b7266-678c-4a23-a439-1a5e7362b604");
            assertThat(j.descriptionText()).contains("Rippling gives businesses");
            assertThat(a.recognize(URI.create("https://ats.rippling.com/rippling/jobs")).map(BoardRef::identifier)).contains("rippling");
        }
    }

    // ==========================================================================================
    @Nested
    class SinglePayload {
        @Test
        void workable() {
            var a = new SinglePayloadAdapters.Workable();
            assertThat(a.recognize(URI.create("https://apply.workable.com/huggingface/")).map(BoardRef::identifier)).contains("huggingface");
            assertThat(a.recognize(URI.create("https://apply.workable.com/j/81B46579FE"))).isEmpty();
            ListingResult r = a.list(a.parseIdentifier("huggingface"),
                    new FixtureHttp().on("GET", "workable", json(fixture("workable-widget.json"))).context());
            assertThat(r.complete()).isTrue();
            assertThat(r.employerName()).isEqualTo("Hugging Face");
            NormalizedJob j = first(r).job();
            assertThat(j.externalId()).isEqualTo("81B46579FE");
            assertThat(j.employerName()).isEqualTo("Hugging Face");
            assertThat(j.remoteType()).isEqualTo("REMOTE");
            assertThat(j.city()).isEqualTo("Paris");
            assertThat(j.postedAt()).isEqualTo(Instant.parse("2026-05-29T00:00:00Z"));
            assertThat(j.applicationUrl()).isEqualTo("https://apply.workable.com/j/81B46579FE/apply");
        }

        @Test
        void gem() {
            var a = new SinglePayloadAdapters.Gem();
            ListingResult r = a.list(a.parseIdentifier("gem"), new FixtureHttp().on("GET", "gem", json(fixture("gem-posts.json"))).context());
            assertThat(r.entries()).hasSize(2);
            NormalizedJob j = first(r).job();
            assertThat(j.externalId()).isEqualTo("4965519002");
            assertThat(j.requisitionId()).isEqualTo("95");
            assertThat(j.remoteType()).isEqualTo("HYBRID");
            assertThat(j.applicationUrl()).isEqualTo("https://jobs.gem.com/gem/4965519002");
            // Gem states compensation only in prose: no structured salary is invented from it.
            assertThat(j.salaryMin()).isNull();
        }

        @Test
        void pinpointOnlyStatesSalaryWhenThePostingMakesItVisible() {
            var a = new SinglePayloadAdapters.Pinpoint();
            assertThat(a.recognize(URI.create("https://workwithus.pinpointhq.com/en/postings/x")).map(BoardRef::identifier))
                    .contains("workwithus");
            ListingResult r = a.list(a.parseIdentifier("workwithus"),
                    new FixtureHttp().on("GET", "pinpoint", json(fixture("pinpoint-postings.json"))).context());
            NormalizedJob hidden = r.entries().get(0).job();
            NormalizedJob visible = r.entries().get(1).job();
            assertThat(hidden.salaryMin()).isNull();
            assertThat(visible.salaryMin()).isEqualByComparingTo(new BigDecimal("70000"));
            assertThat(visible.salaryMax()).isEqualByComparingTo(new BigDecimal("90000"));
            assertThat(visible.salaryCurrency()).isEqualTo("GBP");
            assertThat(visible.requisitionId()).isEqualTo("PIN-0408");
            // Pinpoint lists no posting date: unknown stays unknown.
            assertThat(visible.postedAt()).isNull();
        }

        @Test
        void recruiteeKeepsTheEmployersOwnApplyUrlAndOnlyRealSalaries() {
            var a = new SinglePayloadAdapters.Recruitee();
            ListingResult r = a.list(a.parseIdentifier("vandebron"),
                    new FixtureHttp().on("GET", "recruitee", json(fixture("recruitee-offers.json"))).context());
            NormalizedJob noSalary = r.entries().get(0).job();
            NormalizedJob salary = r.entries().get(1).job();
            assertThat(noSalary.employerName()).isEqualTo("Vandebron");
            assertThat(noSalary.applicationUrl()).isEqualTo("https://werkenbij.vandebron.nl/o/manager-backoffice/c/new");
            assertThat(noSalary.remoteType()).isEqualTo("HYBRID");
            assertThat(noSalary.postedAt()).isEqualTo(Instant.parse("2026-10-07T09:48:39Z"));
            assertThat(noSalary.salaryMin()).isNull();
            assertThat(noSalary.salaryCurrency()).isNull();
            assertThat(salary.salaryMin()).isEqualByComparingTo("3000");
            assertThat(salary.salaryPeriod()).isEqualTo("month");
        }

        @Test
        void zohoNeverParsesALocaleAmbiguousDate() {
            var a = new SinglePayloadAdapters.ZohoRecruit();
            assertThat(a.recognize(URI.create("https://sehyog.zohorecruit.in/jobs/Careers")).map(BoardRef::identifier))
                    .contains("sehyog.zohorecruit.in");
            assertThatThrownBy(() -> a.parseIdentifier("evil.example.com")).isInstanceOf(BoardConfigException.class);
            ListingResult r = a.list(a.parseIdentifier("sehyog.zohorecruit.in"),
                    new FixtureHttp().on("GET", "zohorecruit", json(fixture("zoho-openings.json"))).context());
            assertThat(r.entries()).hasSize(3);
            assertThat(r.complete()).as("pagination unobserved: never a complete listing").isFalse();
            assertThat(r.failure()).isNull();
            NormalizedJob j = first(r).job();
            assertThat(j.externalId()).isEqualTo("167689000000579270");
            assertThat(j.remoteType()).isEqualTo("REMOTE");
            assertThat(j.postedAt()).as("Date_Opened is 02/06/2026 — day/month order unknown").isNull();
            assertThat(j.applicationUrl()).startsWith("https://jobs.sehyog.co.uk/jobs/Careers/167689000000579270/");
        }

        @Test
        void aNonSuccessZohoEnvelopeIsMalformed() {
            var a = new SinglePayloadAdapters.ZohoRecruit();
            assertThat(a.list(a.parseIdentifier("acme.zohorecruit.com"),
                    new FixtureHttp().on("GET", "zohorecruit", json("{\"code\":\"error\"}")).context()).failure())
                    .isEqualTo(FailureCategory.MALFORMED_RESPONSE);
        }
    }

    // ==========================================================================================
    @Nested
    class DetailFetch {
        @Test
        void smartRecruitersPaginatesOnTotalFoundAndReadsJobAdSections() {
            var a = new DetailFetchAdapters.SmartRecruiters();
            BoardRef b = a.parseIdentifier("BoschGroup");
            FixtureHttp f = new FixtureHttp().on("GET", "/postings/", json(fixture("smartrecruiters-detail.json")))
                    .on("GET", "/postings?", json(fixture("smartrecruiters-list.json").replace("4932", "2")));
            ListingResult r = a.list(b, f.context());
            assertThat(r.complete()).isTrue();
            assertThat(r.employerName()).isEqualTo("Bosch Group");
            NormalizedJob j = detail(a, b, first(r), f);
            assertThat(j.title()).isEqualTo("OPERADOR (Prazo determinado)");
            assertThat(j.requisitionId()).isEqualTo("REF298230D");
            assertThat(j.locationRaw()).isEqualTo("Curitiba, PR, Brazil");
            assertThat(j.applicationUrl()).startsWith("https://jobs.smartrecruiters.com/BoschGroup/744000154830479");
            assertThat(j.descriptionText()).contains("Bosch");
            // With 4932 reported, one 100-job page cannot be complete under a one-page cap.
            FixtureHttp big = new FixtureHttp().on("GET", "/postings?", json(fixture("smartrecruiters-list.json")));
            assertThat(a.list(b, big.context(1)).complete()).isFalse();
        }

        @Test
        void oracleBuildsTheCandidateExperienceUrlAndReadsTheDetailResource() {
            var a = new DetailFetchAdapters.OracleCloud();
            assertThat(a.recognize(URI.create("https://eeho.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_45001/job/331138"))
                    .map(BoardRef::identifier)).contains("eeho.fa.us2.oraclecloud.com/CX_45001");
            assertThatThrownBy(() -> a.parseIdentifier("eeho.fa.us2.oraclecloud.com")).isInstanceOf(BoardConfigException.class);
            BoardRef b = a.parseIdentifier("eeho.fa.us2.oraclecloud.com/CX_45001");
            FixtureHttp f = new FixtureHttp().on("GET", "Details", json(fixture("oracle-detail.json")))
                    .on("GET", "recruitingCEJobRequisitions", json(fixture("oracle-list.json").replace("2336", "2")));
            ListingResult r = a.list(b, f.context());
            assertThat(r.complete()).isTrue();
            assertThat(r.entries()).extracting(ListingEntry::externalId).containsExactly("331138", "335818");
            NormalizedJob j = detail(a, b, first(r), f);
            assertThat(j.applicationUrl()).isEqualTo("https://eeho.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_45001/job/331138");
            assertThat(j.postedAt()).isEqualTo(Instant.parse("2026-10-08T20:59:40Z"));
            assertThat(j.descriptionText()).contains("Oracle Health");
        }

        @Test
        void ultiProReadsTheEmbeddedOpportunityAndOnlyVisiblePay() {
            var a = new DetailFetchAdapters.UltiPro();
            assertThat(a.recognize(URI.create("https://recruiting2.ultipro.com/HEL1006HELI/JobBoard/73d20f56-33cd-4859-a6e3-7c858a9a8241/?q=&o=postedDateDesc"))
                    .map(BoardRef::identifier)).contains("recruiting2.ultipro.com/HEL1006HELI/73d20f56-33cd-4859-a6e3-7c858a9a8241");
            BoardRef b = a.parseIdentifier("recruiting2.ultipro.com/HEL1006HELI/73d20f56-33cd-4859-a6e3-7c858a9a8241");
            FixtureHttp f = new FixtureHttp()
                    .on("POST", "LoadSearchResults", json(fixture("ultipro-list.json").replace("\"totalCount\": 124", "\"totalCount\": 3")))
                    .on("GET", "OpportunityDetail", html(fixture("ultipro-detail.html")));
            ListingResult r = a.list(b, f.context());
            assertThat(r.complete()).isTrue();
            assertThat(f.requests.get(0).body()).contains("\"Top\":50", "\"Skip\":0");
            NormalizedJob j = detail(a, b, first(r), f);
            assertThat(j.title()).isEqualTo("Traveling Project Engineer");
            assertThat(j.requisitionId()).isEqualTo("TRAVE008142");
            assertThat(j.employmentType()).isEqualTo("Full-time");
            assertThat(j.salaryMin()).as("PayRangeVisible=false").isNull();
            assertThat(j.descriptionText()).startsWith("Our Traveling Project Engineer");
        }

        @Test
        void phenomUsesTheWidgetsProtocolAndPrefersTheStructuredListingDate() {
            var a = new DetailFetchAdapters.Phenom();
            BoardRef b = a.parseIdentifier("careers.phenom.com/us/en");
            FixtureHttp f = new FixtureHttp()
                    .on("POST", "widgets", json(fixture("phenom-widgets.json").replace("\"totalHits\": 38", "\"totalHits\": 2")))
                    .on("GET", "/job/", html(fixture("phenom-detail.html")));
            ListingResult r = a.list(b, f.context());
            assertThat(r.complete()).isTrue();
            assertThat(f.requests.get(0).body()).contains("\"ddoKey\":\"refineSearch\"", "\"from\":0");
            NormalizedJob j = detail(a, b, first(r), f);
            assertThat(j.title()).isEqualTo("PDE II - Fullstack");
            assertThat(j.postedAt()).isEqualTo(Instant.parse("2026-08-05T09:34:56.310Z"));
            assertThat(j.descriptionText()).startsWith("Job Requirements").doesNotContain("<strong>");
            assertThat(j.applicationUrl()).isEqualTo("https://careers.phenom.com/us/en/job/P-104654");
            assertThatThrownBy(() -> a.parseIdentifier("careers.phenom.com")).isInstanceOf(BoardConfigException.class);
        }

        @Test
        void breezyListsFromJsonAndReadsTheDescriptionBlock() {
            var a = new DetailFetchAdapters.BreezyHr();
            BoardRef b = a.parseIdentifier("breezy");
            FixtureHttp f = new FixtureHttp().on("GET", "/json", json(fixture("breezy-list.json")))
                    .on("GET", "/p/", html(fixture("breezy-detail.html")));
            ListingResult r = a.list(b, f.context());
            assertThat(r.employerName()).isEqualTo("MS Breezy Trial");
            NormalizedJob j = detail(a, b, first(r), f);
            assertThat(j.externalId()).isEqualTo("98323abf2296");
            assertThat(j.descriptionText()).isEqualTo("Roses and Rainbows until 30 days in.");
            // Breezy's salary is free text ("$0.05 – $0.06 / hour"): the currency is ambiguous, so none is stated.
            assertThat(j.salaryMin()).isNull();
            assertThatThrownBy(() -> a.detail(b, ListingEntry.partial("x", "https://breezy.breezy.hr/p/x", "T", Map.of()),
                    new FixtureHttp().on("GET", "/p/", html("<html><body>nothing</body></html>")).context()))
                    .isInstanceOf(Parsing.MalformedException.class);
        }

        @Test
        void paylocityReadsPageDataThenJsonLd() {
            var a = new DetailFetchAdapters.Paylocity();
            assertThat(a.recognize(URI.create("https://recruiting.paylocity.com/recruiting/jobs/All/3876b9cb-3f4c-41a0-a88e-91ef0efab17e"))
                    .map(BoardRef::identifier)).contains("3876b9cb-3f4c-41a0-a88e-91ef0efab17e");
            BoardRef b = a.parseIdentifier("3876b9cb-3f4c-41a0-a88e-91ef0efab17e");
            FixtureHttp f = new FixtureHttp().on("GET", "Jobs/All", html(fixture("paylocity-list.html")))
                    .on("GET", "Details", html(fixture("paylocity-detail.html")));
            ListingResult r = a.list(b, f.context());
            assertThat(r.complete()).isTrue();
            assertThat(r.entries()).hasSize(13);
            assertThat(r.employerName()).isEqualTo("BCI Burke Company LLC");
            NormalizedJob j = detail(a, b, first(r), f);
            assertThat(j.title()).isEqualTo("Marketing Events Intern");
            assertThat(j.postedAt()).isEqualTo(Instant.parse("2026-10-05T19:44:15Z"));
            assertThat(j.descriptionText()).contains("playground equipment");
        }
    }

    // ==========================================================================================
    @Nested
    class Markup {
        @Test
        void icimsFollowsRelNextUntilTheEmptyStateAndReadsJsonLd() {
            var a = new MarkupAdapters.Icims();
            BoardRef b = a.parseIdentifier("us-erac.icims.com");
            FixtureHttp f = new FixtureHttp().on("GET", "pr=0", html(fixture("icims-list.html")))
                    .on("GET", "pr=1", html(fixture("icims-list-page2.html")))
                    .on("GET", "pr=2", html("<html><body><div class='iCIMS_Message'>No jobs</div></body></html>"))
                    .on("GET", "/job?in_iframe", html(fixture("icims-detail.html")));
            ListingResult r = a.list(b, f.context());
            assertThat(r.complete()).isTrue();
            assertThat(r.pagesFetched()).isEqualTo(3);
            assertThat(r.entries()).hasSize(60);
            NormalizedJob j = detail(a, b, first(r), f);
            assertThat(j.externalId()).isEqualTo("568973");
            assertThat(j.title()).isEqualTo("Automotive Technician - Sherwood, OR");
            assertThat(j.employerName()).isEqualTo("Enterprise Holdings");
            assertThat(j.city()).as("'UNAVAILABLE' placeholder is not a city").isNull();
            assertThat(j.salaryCurrency()).as("currency without an amount is not a salary").isNull();
            assertThat(j.applicationUrl()).isEqualTo("https://us-erac.icims.com/jobs/568973/automotive-technician---sherwood%2c-or/job");
            assertThat(a.recognize(URI.create("https://careers.icims.com/sales"))).isEmpty();
        }

        @Test
        void icimsWithoutAJobTableOrEmptyStateIsMalformed() {
            var a = new MarkupAdapters.Icims();
            assertThat(a.list(a.parseIdentifier("acme.icims.com"),
                    new FixtureHttp().on("GET", "pr=0", html("<html><body>login required</body></html>")).context()).failure())
                    .isEqualTo(FailureCategory.MALFORMED_RESPONSE);
        }

        @Test
        void jobviteDedupesFeaturedJobsAndParsesTheDetailPage() {
            var a = new MarkupAdapters.Jobvite();
            BoardRef b = a.parseIdentifier("oneco");
            FixtureHttp f = new FixtureHttp().on("GET", "/oneco/jobs", html(fixture("jobvite-list.html")))
                    .on("GET", "/oneco/job/", html(fixture("jobvite-detail.html")));
            ListingResult r = a.list(b, f.context());
            assertThat(r.complete()).isTrue();
            assertThat(r.entries()).hasSize(40);
            assertThat(r.entries()).extracting(ListingEntry::externalId).doesNotHaveDuplicates();
            NormalizedJob j = detail(a, b, first(r), f);
            assertThat(j.title()).isEqualTo("R&D Engineer");
            assertThat(j.applicationUrl()).startsWith("https://jobs.jobvite.com/oneco/job/");
        }

        @Test
        void jobviteWithUnrecognisedPaginationIsPartialNotComplete() {
            var a = new MarkupAdapters.Jobvite();
            String paged = fixture("jobvite-list.html").replace("</body>",
                    "<div class=\"jv-pager\"><a href=\"/oneco/jobs?p=1\">2</a></div></body>");
            ListingResult r = a.list(a.parseIdentifier("oneco"), new FixtureHttp().on("GET", "/oneco/jobs", html(paged)).context());
            assertThat(r.complete()).isFalse();
            assertThat(r.entries()).hasSize(40);
        }

        @Test
        void jazzHrFeedIsCompleteAndSkipsNonOpenJobs() {
            var a = new MarkupAdapters.JazzHr();
            assertThat(a.recognize(URI.create("https://labelmaster.applytojob.com/apply/jobs/")).map(BoardRef::identifier))
                    .contains("labelmaster");
            String feed = fixture("jazzhr-feed.xml").replaceFirst("<status><!\\[CDATA\\[Open]]>", "<status><![CDATA[Closed]]>");
            ListingResult r = a.list(a.parseIdentifier("labelmaster"),
                    new FixtureHttp().on("GET", "app.jazz.co", ok("text/html; charset=UTF-8", feed)).context()); // live feed label
            assertThat(r.complete()).isTrue();
            assertThat(r.employerName()).isEqualTo("Labelmaster");
            assertThat(r.entries()).hasSize(1);
            NormalizedJob j = first(r).job();
            assertThat(j.title()).isEqualTo("IT Support Specialist");
            assertThat(j.applicationUrl()).isEqualTo("https://labelmaster.applytojob.com/apply/hmwuAoh3i3/IT-Support-Specialist");
            assertThat(j.postedAt()).as("the feed carries no posting date").isNull();
        }

        @Test
        void joinUsesNextDataPaginationAndJsonLdDetail() {
            var a = new MarkupAdapters.Join();
            BoardRef b = a.parseIdentifier("join");
            FixtureHttp f = new FixtureHttp().on("GET", "join.com/companies/join/", html(fixture("join-detail.html")))
                    .on("GET", "join.com/companies/join", html(fixture("join-list.html")));
            ListingResult r = a.list(b, f.context());
            assertThat(r.complete()).isTrue();
            assertThat(r.employerName()).isEqualTo("JOIN");
            NormalizedJob j = detail(a, b, first(r), f);
            assertThat(j.externalId()).isEqualTo("16805344");
            assertThat(j.salaryMin()).isEqualByComparingTo("80000");
            assertThat(j.salaryCurrency()).isEqualTo("EUR");
            assertThat(j.remoteType()).isEqualTo("HYBRID");
        }

        @Test
        void teamtailorRssIsNeverTreatedAsACompleteListing() {
            var a = new MarkupAdapters.Teamtailor();
            ListingResult r = a.list(a.parseIdentifier("career.teamtailor.com"),
                    new FixtureHttp().on("GET", "jobs.rss", ok("application/rss+xml; charset=utf-8", fixture("teamtailor.rss"))).context());
            assertThat(r.complete()).isFalse();
            assertThat(r.failure()).isNull();
            assertThat(r.entries()).hasSize(3);
            NormalizedJob j = first(r).job();
            assertThat(j.externalId()).isEqualTo("8118064");
            assertThat(j.remoteType()).isEqualTo("HYBRID");
            assertThat(j.postedAt()).isEqualTo(Instant.parse("2026-07-24T07:05:16Z"));
            assertThat(j.city()).isEqualTo("London");
        }

        @Test
        void jazzHrHtmlPageInsteadOfTheFeedIsMalformedNotParsed() {
            var a = new MarkupAdapters.JazzHr();
            ListingResult r = a.list(a.parseIdentifier("acme"), new FixtureHttp().on("GET", "app.jazz.co",
                    ok("text/html; charset=UTF-8", "<html><body><h1>Sign in</h1></body></html>")).context());
            assertThat(r.failure()).isEqualTo(FailureCategory.MALFORMED_RESPONSE);
            assertThat(r.entries()).isEmpty();
        }

        @Test
        void jobvitePostingWithoutADescriptionKeepsItUnknown() {
            var a = new MarkupAdapters.Jobvite();
            BoardRef b = a.parseIdentifier("oneco");
            org.jsoup.nodes.Document page = org.jsoup.Jsoup.parse(fixture("jobvite-detail.html"));
            page.select("div.jv-job-detail-description").remove();
            String noDescription = page.outerHtml();
            assertThat(noDescription).doesNotContain("jv-job-detail-description");
            FixtureHttp f = new FixtureHttp().on("GET", "/oneco/jobs", html(fixture("jobvite-list.html")))
                    .on("GET", "/oneco/job/", html(noDescription));
            NormalizedJob j = detail(a, b, first(a.list(b, f.context())), f);
            assertThat(j.title()).isEqualTo("R&D Engineer");
            assertThat(j.descriptionText()).isNull();
        }

        @Test
        void jobviteNonJobPageIsMalformed() {
            var a = new MarkupAdapters.Jobvite();
            BoardRef b = a.parseIdentifier("oneco");
            FixtureHttp f = new FixtureHttp().on("GET", "/oneco/jobs", html(fixture("jobvite-list.html")))
                    .on("GET", "/oneco/job/", html("<html><body><p>Not found</p></body></html>"));
            ListingEntry e = first(a.list(b, f.context()));
            assertThatThrownBy(() -> a.detail(b, e, f.context())).isInstanceOf(Parsing.MalformedException.class);
        }

        @Test
        void wrongContentTypeIsRejectedNotParsed() {
            var a = new MarkupAdapters.JazzHr();
            assertThat(a.list(a.parseIdentifier("acme"),
                    new FixtureHttp().on("GET", "app.jazz.co", ok("application/octet-stream", "<jobs/>")).context()).failure())
                    .isEqualTo(FailureCategory.RESPONSE_REJECTED);
        }
    }

    @Test
    void everyListingParserToleratesDuplicateIngestionOfTheSamePage() {
        var a = new SinglePayloadAdapters.Gem();
        String doubled = "[" + fixture("gem-posts.json").strip().replaceAll("^\\[|]$", "") + ","
                + fixture("gem-posts.json").strip().replaceAll("^\\[|]$", "") + "]";
        ListingResult r = a.list(a.parseIdentifier("gem"), new FixtureHttp().on("GET", "gem", json(doubled)).context());
        assertThat(r.entries()).hasSize(2);
        assertThat(List.copyOf(r.entries())).extracting(ListingEntry::externalId).doesNotHaveDuplicates();
    }
}
