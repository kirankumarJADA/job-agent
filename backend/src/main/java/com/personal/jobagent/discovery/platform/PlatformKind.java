package com.personal.jobagent.discovery.platform;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The FIND coverage manifest: every applicant-tracking system named on
 * Tsenta's public supported-platform list (https://tsenta.com/ai-disclosure,
 * checked 2026-10-10 — "Workday Greenhouse Lever Ashby Rippling iCIMS BambooHR
 * Workable JazzHR Jobvite BreezyHR Oracle Cloud SmartRecruiters Paylocity
 * UltiPro ADP Dover Gem Zoho Recruit BrassRing CareerPlug Dayforce Hireology
 * Join Phenom Pinpoint Polymer Recruitee SAP SuccessFactors Teamtailor"), plus
 * four separate aggregator sources that are not themselves on that list:
 * Tsenta; FreeHire (https://freehire.me, its own independent catalogue
 * pulled from 92 ATS platforms through FreeHire's own public API — see
 * {@code FreeHireAdapter}'s javadoc); Arbeitnow
 * (https://www.arbeitnow.com, its own independent catalogue with a free,
 * keyless, documented public API — see {@code ArbeitnowAdapter}'s javadoc);
 * and Adzuna (https://www.adzuna.com, a keyed, documented public search API
 * covering many countries through the same endpoint shape — one registered
 * app_id/app_key pair, a country code in the path — see {@code
 * AdzunaAdapter}'s javadoc for why this is Robin's region-aware source).
 *
 * <p>The {@link #name()} of each constant is the persisted
 * {@code job_sources.kind} value (V037 widens the check constraint to exactly
 * this set plus the three legacy non-platform kinds).
 *
 * <p>{@link Readiness} is a statement about the IMPLEMENTATION, established in
 * Phase 8.2.5 and enforced by {@code PlatformCoverageAuditTest}:
 * <ul>
 *   <li>{@code LIVE_VALIDATED} — a real adapter exists, its parsers are tested
 *       against fixtures captured from a real public board, and the endpoint
 *       was fetched live during this phase;</li>
 *   <li>{@code UNVERIFIED} — no adapter is registered: either no public
 *       listing endpoint could be confirmed or no public tenant with live
 *       openings was found to validate the job schema against;</li>
 *   <li>{@code BLOCKED} — the public listing is only reachable by working
 *       around an access control (session-bound anti-forgery tokens,
 *       bot challenges), which this project does not do.</li>
 * </ul>
 * The candidate-facing coverage status (SUPPORTED / NEEDS_BOARD_CONFIGURATION /
 * TEMPORARILY_UNAVAILABLE / BLOCKED / UNVERIFIED) is derived from readiness and
 * the configured sources by {@link PlatformCoverageService}.
 */
public enum PlatformKind {
    // ---- Group A ---------------------------------------------------------------------------
    WORKDAY("Workday", Group.A, Readiness.LIVE_VALIDATED,
            "tenant.wdN/site, e.g. nvidia.wd5/NVIDIAExternalCareerSite (derived from a *.myworkdayjobs.com URL)",
            List.of("myworkdayjobs", "workday recruiting")),
    GREENHOUSE("Greenhouse", Group.A, Readiness.LIVE_VALIDATED,
            "board token, e.g. stripe (from boards.greenhouse.io/{token})", List.of("greenhouse.io")),
    LEVER("Lever", Group.A, Readiness.LIVE_VALIDATED,
            "site name, e.g. palantir; prefix eu: for the EU data centre", List.of("lever.co")),
    ASHBY("Ashby", Group.A, Readiness.LIVE_VALIDATED,
            "job board name, e.g. ramp (from jobs.ashbyhq.com/{board})", List.of("ashbyhq")),
    RIPPLING("Rippling", Group.A, Readiness.LIVE_VALIDATED,
            "board slug, e.g. rippling (from ats.rippling.com/{slug}/jobs)", List.of("rippling ats")),
    ICIMS("iCIMS", Group.A, Readiness.LIVE_VALIDATED,
            "portal host, e.g. us-erac.icims.com", List.of("icims.com")),
    BAMBOOHR("BambooHR", Group.A, Readiness.UNVERIFIED,
            "company subdomain, e.g. acme (acme.bamboohr.com)", List.of("bamboo hr")),
    WORKABLE("Workable", Group.A, Readiness.LIVE_VALIDATED,
            "account name, e.g. huggingface (apply.workable.com/{account})", List.of("apply.workable.com")),
    JAZZHR("JazzHR", Group.A, Readiness.LIVE_VALIDATED,
            "applytojob subdomain, e.g. labelmaster (labelmaster.applytojob.com)", List.of("jazz", "applytojob", "resumator")),
    JOBVITE("Jobvite", Group.A, Readiness.LIVE_VALIDATED,
            "company slug, e.g. oneco (jobs.jobvite.com/{slug})", List.of("jobvite.com")),
    // ---- Group B ---------------------------------------------------------------------------
    BREEZYHR("BreezyHR", Group.B, Readiness.LIVE_VALIDATED,
            "portal subdomain, e.g. acme (acme.breezy.hr)", List.of("breezy", "breezy hr")),
    ORACLE_CLOUD("Oracle Cloud", Group.B, Readiness.LIVE_VALIDATED,
            "host/siteNumber, e.g. eeho.fa.us2.oraclecloud.com/CX_45001",
            List.of("oracle recruiting cloud", "oracle hcm", "oracle candidate experience", "taleo (oracle cloud)")),
    SMARTRECRUITERS("SmartRecruiters", Group.B, Readiness.LIVE_VALIDATED,
            "company identifier, e.g. BoschGroup", List.of("smart recruiters")),
    PAYLOCITY("Paylocity", Group.B, Readiness.LIVE_VALIDATED,
            "company GUID from recruiting.paylocity.com/Recruiting/Jobs/All/{guid}", List.of("paylocity recruiting")),
    ULTIPRO("UltiPro", Group.B, Readiness.LIVE_VALIDATED,
            "host/tenant/boardGuid, e.g. recruiting2.ultipro.com/HEL1006HELI/73d20f56-33cd-4859-a6e3-7c858a9a8241",
            List.of("ukg pro", "ukg", "ultimate software")),
    ADP("ADP", Group.B, Readiness.UNVERIFIED,
            "Workforce Now career-centre cid (no public tenant validated)",
            List.of("adp workforce now", "adp recruiting management")),
    DOVER("Dover", Group.B, Readiness.UNVERIFIED,
            "careers-page slug (no public listing API confirmed)", List.of("dover ats")),
    GEM("Gem", Group.B, Readiness.LIVE_VALIDATED,
            "job board name, e.g. gem (jobs.gem.com/{board})", List.of("gem ats")),
    ZOHO_RECRUIT("Zoho Recruit", Group.B, Readiness.LIVE_VALIDATED,
            "career-site host, e.g. sehyog.zohorecruit.in", List.of("zohorecruit", "zoho")),
    BRASSRING("BrassRing", Group.B, Readiness.BLOCKED,
            "partnerid/siteid (search requires a session-bound anti-forgery token)",
            List.of("kenexa", "ibm kenexa brassring", "infinite brassring")),
    // ---- Group C ---------------------------------------------------------------------------
    CAREERPLUG("CareerPlug", Group.C, Readiness.UNVERIFIED,
            "company subdomain (no public listing endpoint confirmed)", List.of("career plug")),
    DAYFORCE("Dayforce", Group.C, Readiness.UNVERIFIED,
            "client namespace/job board code (public search API answered 403)", List.of("ceridian dayforce", "dayforcehcm")),
    HIREOLOGY("Hireology", Group.C, Readiness.UNVERIFIED,
            "careers slug (no public tenant or endpoint confirmed)", List.of()),
    JOIN("Join", Group.C, Readiness.LIVE_VALIDATED,
            "company slug, e.g. join (join.com/companies/{slug})", List.of("join.com")),
    PHENOM("Phenom", Group.C, Readiness.LIVE_VALIDATED,
            "careers host/country/language, e.g. careers.phenom.com/us/en", List.of("phenom people")),
    PINPOINT("Pinpoint", Group.C, Readiness.LIVE_VALIDATED,
            "company subdomain, e.g. workwithus (workwithus.pinpointhq.com)", List.of("pinpointhq")),
    POLYMER("Polymer", Group.C, Readiness.UNVERIFIED,
            "organisation slug (public API rejected tested slugs)", List.of("jobs.polymer.co")),
    RECRUITEE("Recruitee", Group.C, Readiness.LIVE_VALIDATED,
            "company subdomain, e.g. vandebron (vandebron.recruitee.com)", List.of("tellent recruitee")),
    SAP_SUCCESSFACTORS("SAP SuccessFactors", Group.C, Readiness.UNVERIFIED,
            "career-site company id (listing is client-rendered; not validated)", List.of("successfactors", "sap sf")),
    TEAMTAILOR("Teamtailor", Group.C, Readiness.LIVE_VALIDATED,
            "career-site host, e.g. career.teamtailor.com", List.of("team tailor")),
    // ---- Aggregator ------------------------------------------------------------------------
    TSENTA("Tsenta", Group.AGGREGATOR, Readiness.LIVE_VALIDATED,
            "listing scope: jobs, jobs/remote, jobs/internships or jobs/entry-level", List.of("tsenta.com")),
    FREEHIRE("FreeHire", Group.AGGREGATOR, Readiness.LIVE_VALIDATED,
            "category facet value, e.g. software_engineering, backend, frontend, fullstack or devops",
            List.of("freehire.me")),
    ARBEITNOW("Arbeitnow", Group.AGGREGATOR, Readiness.LIVE_VALIDATED,
            "fixed identifier 'jobs' — one global catalogue, no per-category partition",
            List.of("arbeitnow.com")),
    ADZUNA("Adzuna", Group.AGGREGATOR, Readiness.LIVE_VALIDATED,
            "ISO-3166-1 alpha-2 country code Adzuna supports, e.g. gb, us, de — one source row per country",
            List.of("adzuna.com"));

    public enum Group { A, B, C, AGGREGATOR }

    public enum Readiness { LIVE_VALIDATED, UNVERIFIED, BLOCKED }

    private final String displayName;
    private final Group group;
    private final Readiness readiness;
    private final String configHint;
    private final List<String> aliases;

    PlatformKind(String displayName, Group group, Readiness readiness, String configHint, List<String> aliases) {
        this.displayName = displayName;
        this.group = group;
        this.readiness = readiness;
        this.configHint = configHint;
        this.aliases = aliases;
    }

    public String displayName() { return displayName; }
    public Group group() { return group; }
    public Readiness readiness() { return readiness; }
    public String configHint() { return configHint; }
    public List<String> aliases() { return aliases; }
    public boolean isAggregator() { return group == Group.AGGREGATOR; }

    /** The 30 ATS platforms (everything except the aggregator). */
    public static List<PlatformKind> atsPlatforms() {
        return Arrays.stream(values()).filter(kind -> !kind.isAggregator()).toList();
    }

    /** Resolve a persisted kind, display name or alias (case- and punctuation-insensitive). */
    public static Optional<PlatformKind> lookup(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String wanted = squash(value);
        for (PlatformKind kind : values()) {
            if (squash(kind.name()).equals(wanted) || squash(kind.displayName).equals(wanted)) return Optional.of(kind);
            for (String alias : kind.aliases) {
                if (squash(alias).equals(wanted)) return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    private static String squash(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
