package com.personal.jobagent.discovery.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Strong-evidence identity across sources; titles never identify a job. */
class JobIdentityTest {

    @ParameterizedTest
    @CsvSource({
            "https://boards.greenhouse.io/acme/jobs/42, greenhouse:acme:42",
            "https://job-boards.greenhouse.io/acme/jobs/42?gh_src=x, greenhouse:acme:42",
            "https://jobs.ashbyhq.com/ramp/8f1e2c/application, ashby:ramp:8f1e2c",
            "https://jobs.lever.co/palantir/6ed76ce8-4156-4b60-b120-403538bd66cd/apply, lever:palantir:6ed76ce8-4156-4b60-b120-403538bd66cd",
            "https://nvidia.wd5.myworkdayjobs.com/NVIDIAExternalCareerSite/job/US-CA-Santa-Clara/Senior-HPC-Storage-Engineer_JR2014997, workday:nvidia:jr2014997",
            "https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/job/US-CA-Santa-Clara/Senior-HPC-Storage-Engineer_JR2014997/apply, workday:nvidia:jr2014997",
            "https://jobs.smartrecruiters.com/BoschGroup/744000154830479-operador-prazo-determinado-, smartrecruiters:boschgroup:744000154830479",
            "https://apply.workable.com/j/81B46579FE/apply, workable:81B46579FE",
            "https://apply.workable.com/huggingface/j/81b46579fe/, workable:81B46579FE",
            "https://workwithus.pinpointhq.com/en/postings/ce6c9e5c-a2d3-42b0-a01e-9edeae315b04, pinpoint:ce6c9e5c-a2d3-42b0-a01e-9edeae315b04",
            "https://sehyog.zohorecruit.in/jobs/Careers/167689000000570023/Senior-English-Tutor, zoho_recruit:167689000000570023",
            "https://jobs.sehyog.co.uk/jobs/Careers/167689000000570023/Senior-English-Tutor?source=CareerSite, zoho_recruit:167689000000570023",
            "https://us-erac.icims.com/jobs/568973/automotive-technician/job, icims:us-erac.icims.com:568973",
            "https://labelmaster.applytojob.com/apply/rdrohp3n7B/Sales-Enablement-Senior-Manager, jazzhr:labelmaster:rdrohp3n7B",
            "https://jobs.jobvite.com/oneco/job/oKsPXfwI, jobvite:oneco:oKsPXfwI",
            "https://breezy.breezy.hr/p/98323abf2296-employee-12, breezyhr:breezy:98323abf2296",
            "https://eeho.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_45001/job/331138, oracle_cloud:eeho.fa.us2.oraclecloud.com:331138",
            "https://recruiting.paylocity.com/Recruiting/Jobs/Details/4561816, paylocity:4561816",
            "https://recruiting2.ultipro.com/HEL1006HELI/JobBoard/73d20f56-33cd-4859-a6e3-7c858a9a8241/OpportunityDetail?opportunityId=B78CFB5F-4c2a-4e97-b737-eacb266bf560, ultipro:b78cfb5f-4c2a-4e97-b737-eacb266bf560",
            "https://ats.rippling.com/rippling/jobs/ef0b7266-678c-4a23-a439-1a5e7362b604, rippling:ef0b7266-678c-4a23-a439-1a5e7362b604",
            "https://jobs.gem.com/gem/4965519002, gem:gem:4965519002",
            "https://join.com/companies/join/16805344-senior-growth-product-marketing-manager, join:16805344",
            "https://career.teamtailor.com/jobs/8118064-uk-account-executive-smb, teamtailor:career.teamtailor.com:8118064",
            "https://boards.greenhouse.io/embed/job_app?for=acme&token=42, greenhouse:acme:42",
            "https://job-boards.greenhouse.io/embed/job_app?for=acme&token=42, greenhouse:acme:42",
            "https://vandebron.recruitee.com/o/manager-backoffice, recruitee:vandebron:manager-backoffice",
            "https://vandebron.recruitee.com/l/en/o/manager-backoffice/c/new, recruitee:vandebron:manager-backoffice",
            "https://careers.phenom.com/us/en/job/12345/senior-engineer, phenom:careers.phenom.com:12345",
            "https://careers.phenompeople.com/us/en/job/67890, phenom:careers.phenompeople.com:67890"
    })
    void platformJobIdsAreReadFromJobAndApplyUrls(String url, String expected) {
        assertThat(JobIdentity.atsKey(url)).isEqualTo(expected);
    }

    @Test
    void urlsThatCarryNoPlatformJobIdHaveNoAtsKey() {
        assertThat(JobIdentity.atsKey("https://tsenta.com/jobs/acme-engineer-8d7f73a6-5a94-4d7a-84c3-4db0ce114498")).isNull();
        assertThat(JobIdentity.atsKey("https://careers.example.com/jobs/engineer")).isNull();
        assertThat(JobIdentity.atsKey("not a url")).isNull();
        assertThat(JobIdentity.atsKey(null)).isNull();
    }

    @Test
    void normalisationIgnoresTrackingCaseFragmentsAndTrailingSlashes() {
        assertThat(JobIdentity.normalizeUrl("HTTPS://Careers.Example.com:443/Jobs/1/?utm_source=x&b=2&a=1#apply"))
                .isEqualTo("https://careers.example.com/Jobs/1?a=1&b=2");
        assertThat(JobIdentity.normalizeUrl("https://x.example.com/a?gclid=1&ref=feed")).isEqualTo("https://x.example.com/a");
        assertThat(JobIdentity.normalizeUrl("javascript:alert(1)")).isNull();
    }

    @Test
    void keysAreOrderedStrongestFirstAndNeverIncludeTitles() {
        UUID source = UUID.randomUUID();
        List<JobIdentity.Key> keys = JobIdentity.keys(source, "42", "https://boards.greenhouse.io/acme/jobs/42",
                "https://boards.greenhouse.io/acme/jobs/42", "Acme Ltd", "REQ-9");
        assertThat(keys).extracting(JobIdentity.Key::type).containsExactly(
                JobIdentity.KeyType.SRC, JobIdentity.KeyType.ATS, JobIdentity.KeyType.REQ, JobIdentity.KeyType.URL);
        assertThat(keys).extracting(JobIdentity.Key::asString)
                .contains("src:" + source + ":42", "ats:greenhouse:acme:42", "req:acme:req-9", "url:https://boards.greenhouse.io/acme/jobs/42")
                .noneMatch(k -> k.toLowerCase().contains("engineer"));
    }

    @Test
    void twoRolesWithTheSameTitleAtTheSameEmployerShareNoKey() {
        List<JobIdentity.Key> a = JobIdentity.keys(UUID.randomUUID(), "1", "https://boards.greenhouse.io/acme/jobs/1",
                "https://boards.greenhouse.io/acme/jobs/1", "Acme", null);
        List<JobIdentity.Key> b = JobIdentity.keys(UUID.randomUUID(), "2", "https://boards.greenhouse.io/acme/jobs/2",
                "https://boards.greenhouse.io/acme/jobs/2", "Acme", null);
        assertThat(a).doesNotContainAnyElementsOf(b);
    }

    @Test
    void anAggregatorListingAndTheAtsPostingShareThePlatformKey() {
        List<JobIdentity.Key> aggregator = JobIdentity.keys(UUID.randomUUID(), "8d7f73a6-5a94-4d7a-84c3-4db0ce114498",
                "https://tsenta.com/jobs/sehyog-academy-senior-english-tutor-8d7f73a6-5a94-4d7a-84c3-4db0ce114498",
                "https://sehyog.zohorecruit.in/jobs/Careers/167689000000570023/Senior-English-Tutor-UK-Curriculum", "Sehyog Academy", null);
        List<JobIdentity.Key> ats = JobIdentity.keys(UUID.randomUUID(), "167689000000570023",
                "https://jobs.sehyog.co.uk/jobs/Careers/167689000000570023/Senior-English-Tutor-UK-Curriculum?source=CareerSite",
                "https://jobs.sehyog.co.uk/jobs/Careers/167689000000570023/Senior-English-Tutor-UK-Curriculum?source=CareerSite",
                null, null);
        assertThat(aggregator).extracting(JobIdentity.Key::asString).contains("ats:zoho_recruit:167689000000570023");
        assertThat(ats).extracting(JobIdentity.Key::asString).contains("ats:zoho_recruit:167689000000570023");
    }

    @Test
    void applicationUrlRankPrefersAtsOverEmployerPagesAndNeverAggregators() {
        assertThat(JobIdentity.applicationUrlRank("https://boards.greenhouse.io/acme/jobs/1")).isEqualTo(3);
        assertThat(JobIdentity.applicationUrlRank("https://careers.acme.com/jobs/1")).isEqualTo(2);
        assertThat(JobIdentity.applicationUrlRank("https://dashboard.tsenta.com/dashboard?apply=x")).isZero();
        assertThat(JobIdentity.applicationUrlRank("https://adzuna.co.uk/details/123")).isEqualTo(1);
        assertThat(JobIdentity.applicationUrlRank("https://www.arbeitnow.com/view/456")).isEqualTo(1);
        assertThat(JobIdentity.applicationUrlRank("https://freehire.co.uk/job/789")).isEqualTo(1);
        assertThat(JobIdentity.applicationUrlRank("https://seek.com.au/job/999")).isEqualTo(1);
        assertThat(JobIdentity.applicationUrlRank("https://www.indeed.com/viewjob?jk=1")).isEqualTo(1);
        assertThat(JobIdentity.applicationUrlRank(null)).isZero();
        assertThat(JobIdentity.isAggregatorUrl("https://tsenta.com/jobs/x")).isTrue();
        assertThat(JobIdentity.isAggregatorUrl("https://notsenta.com/jobs/x")).isFalse();
    }

    @Test
    void canonicalNormalisationHandlesWwwSchemeEquivalencePercentDecodingAndCase() {
        // www. and http/https equivalence
        assertThat(JobIdentity.normalizeUrl("http://www.example.com/jobs/1/"))
                .isEqualTo("https://example.com/jobs/1");
        assertThat(JobIdentity.normalizeUrl("https://example.com/jobs/1"))
                .isEqualTo("https://example.com/jobs/1");

        // RFC 3986 unreserved percent-decoding: %7E -> ~, %41 -> A, %2D -> -
        assertThat(JobIdentity.normalizeUrl("https://example.com/%7Euser/%41pp%2D1"))
                .isEqualTo("https://example.com/~user/App-1");

        // Query parameter values preserve original case
        assertThat(JobIdentity.normalizeUrl("https://example.com/apply?token=SecretToken123&role=SWE"))
                .isEqualTo("https://example.com/apply?role=SWE&token=SecretToken123");

        // Additional tracking parameters stripped (mc_*, trk, in_iframe, oga)
        assertThat(JobIdentity.normalizeUrl("https://example.com/job?mc_cid=123&trk=feed&in_iframe=1&oga=web&keep=true"))
                .isEqualTo("https://example.com/job?keep=true");
    }

    @Test
    void dualReadLookupKeysIncludesBothCanonicalAndLegacyForms() {
        UUID source = UUID.randomUUID();
        String url = "http://www.example.com/jobs/1/?token=ABC";
        List<JobIdentity.Key> canonicalKeys = JobIdentity.keys(source, "1", url, url, null, null);
        List<JobIdentity.Key> lookupKeys = JobIdentity.lookupKeys(source, "1", url, url, null, null);

        // Canonical form has www stripped, https, and preserved case
        assertThat(canonicalKeys).extracting(JobIdentity.Key::asString)
                .contains("url:https://example.com/jobs/1?token=ABC");

        // Lookup keys include both canonical and pre-Wave-1 legacy forms
        assertThat(lookupKeys).extracting(JobIdentity.Key::asString)
                .contains("url:https://example.com/jobs/1?token=ABC")
                .contains("url:http://www.example.com/jobs/1?token=abc");
    }

    @Test
    void genericUrlPathQueryDistinguishesJobsWithoutCollidingExternalId() {
        String jobA = "https://example.com/jobs?gh_jid=111";
        String jobB = "https://example.com/jobs?gh_jid=222";
        String normA = JobIdentity.normalizeUrl(jobA);
        String normB = JobIdentity.normalizeUrl(jobB);
        assertThat(normA).isNotEqualTo(normB);
        assertThat(normA).contains("gh_jid=111");
        assertThat(normB).contains("gh_jid=222");
    }
}
