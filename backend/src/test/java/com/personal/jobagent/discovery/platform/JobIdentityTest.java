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
            "https://career.teamtailor.com/jobs/8118064-uk-account-executive-smb, teamtailor:career.teamtailor.com:8118064"
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
        assertThat(JobIdentity.applicationUrlRank(null)).isZero();
        assertThat(JobIdentity.isAggregatorUrl("https://tsenta.com/jobs/x")).isTrue();
        assertThat(JobIdentity.isAggregatorUrl("https://notsenta.com/jobs/x")).isFalse();
    }
}
