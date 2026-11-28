package com.personal.jobagent.jobs;

import com.personal.jobagent.preferences.PreferenceSetRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Evaluates hard (boolean) filter criteria BEFORE the expensive scoring
 * pipeline runs. Every rejection produces a structured
 * {@link HardFilterResult.FilterReason} so the user knows exactly why a
 * job was excluded.
 *
 * <h3>Design rules</h3>
 * <ul>
 *   <li>An empty or null preference list means "no constraint" — the filter
 *       is permissive, not restrictive. Users only filter when they have
 *       expressed a preference.</li>
 *   <li>A job field that is null or empty is treated as "unknown". When the
 *       user HAS expressed a preference but the job data is missing, the
 *       filter is LENIENT by default — the job passes. This avoids false
 *       rejections for jobs with incomplete data. The exception is salary:
 *       if the user set a minimum and the job declares a max below it, that
 *       is a hard reject; but if the job declares no salary at all, it
 *       passes (benefit of the doubt).</li>
 *   <li>All string comparisons are case-insensitive.</li>
 *   <li>Company size and industry require a lookup via company_id → companies
 *       table. If the job has no company_id, those filters pass.</li>
 * </ul>
 */
@Service
public class HardFilterService {

    private static final Logger log = LoggerFactory.getLogger(HardFilterService.class);

    private final JdbcTemplate db;

    public HardFilterService(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * Run all hard filters. Returns {@link HardFilterResult#pass()} when
     * every criterion is satisfied or unconstrained; otherwise a reject
     * result with every failing reason. Missing fields pass leniently for
     * general discovery browsing.
     */
    public HardFilterResult evaluate(JobRecord job, PreferenceSetRecord prefs) {
        return evaluateInternal(job, prefs, null, false);
    }

    /**
     * Stage 3I Phase 8: Strict hard-filter evaluation for autonomous apply /
     * standing authorization. Missing or UNKNOWN salary, location, workplace
     * type, employment type, experience level, or unconfirmed sponsorship for
     * constrained dimensions fails closed instead of receiving the benefit
     * of the doubt.
     */
    public HardFilterResult evaluateStrict(JobRecord job, PreferenceSetRecord prefs) {
        return evaluateInternal(job, prefs, null, true);
    }

    public HardFilterResult evaluateStrict(JobRecord job, PreferenceSetRecord prefs, List<String> allowedCountries) {
        return evaluateInternal(job, prefs, allowedCountries, true);
    }

    private HardFilterResult evaluateInternal(JobRecord job, PreferenceSetRecord prefs,
                                              List<String> allowedCountries, boolean strict) {
        if (prefs == null) {
            if (!strict) {
                return HardFilterResult.pass();
            }
            return HardFilterResult.reject(List.of(new HardFilterResult.FilterReason(
                    "PREFERENCES", "Active preference set required", "NONE",
                    "Strict autonomous eligibility requires an active preference set.")));
        }

        List<HardFilterResult.FilterReason> reasons = new ArrayList<>();

        checkTitles(job, prefs, reasons, strict);
        checkLocation(job, prefs, reasons, strict);
        checkAllowedCountries(job, allowedCountries, reasons, strict);
        checkRemoteType(job, prefs, reasons, strict);
        checkEmploymentType(job, prefs, reasons, strict);
        checkExperienceLevel(job, prefs, reasons, strict);
        checkSalaryMin(job, prefs, reasons, strict);
        checkSponsorshipPolicy(job, prefs, reasons, strict);
        checkCompanyFilters(job, prefs, reasons);
        checkExcludedKeywords(job, prefs, reasons);

        if (reasons.isEmpty()) {
            return HardFilterResult.pass();
        }

        // Sort by criterion for deterministic output
        reasons.sort((a, b) -> a.criterion().compareTo(b.criterion()));
        return HardFilterResult.reject(reasons);
    }

    // ── Individual filter methods ────────────────────────────────────

    private static final java.util.Set<String> UK_SYNONYMS = java.util.Set.of(
            "uk", "gb", "united kingdom", "great britain", "england", "scotland", "wales", "northern ireland");

    private static final java.util.regex.Pattern UK_PATTERN = java.util.regex.Pattern.compile(
            "\\b(uk|gb|united kingdom|great britain|england|scotland|wales|northern ireland|london|manchester|birmingham|edinburgh|bristol|cambridge|oxford|leeds|glasgow|belfast|cardiff)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private void checkTitles(JobRecord job, PreferenceSetRecord prefs,
                             List<HardFilterResult.FilterReason> reasons, boolean strict) {
        List<String> allowedTitles = prefs.titles();
        if (allowedTitles == null || allowedTitles.isEmpty()) return;

        String jobTitle = job.title();
        if (isBlank(jobTitle)) {
            if (strict) {
                reasons.add(new HardFilterResult.FilterReason(
                        "TITLE",
                        String.join(", ", allowedTitles),
                        "unknown",
                        "Job title is missing; cannot verify role fit automatically."
                ));
            }
            return;
        }

        boolean matched = matchesAnyTitle(jobTitle, allowedTitles);
        if (!matched) {
            reasons.add(new HardFilterResult.FilterReason(
                    "TITLE",
                    String.join(", ", allowedTitles),
                    jobTitle,
                    "Job title '" + jobTitle + "' does not match your preferred titles."
            ));
        }
    }

    public static boolean matchesAnyTitle(String jobTitle, List<String> allowedTitles) {
        if (jobTitle == null || jobTitle.isBlank() || allowedTitles == null || allowedTitles.isEmpty()) {
            return false;
        }
        List<String> expandedAllowed = TitleSynonymRegistry.expandTitles(allowedTitles);
        String jobTitleClean = normalizeTitle(jobTitle);
        for (String allowed : expandedAllowed) {
            if (allowed == null || allowed.isBlank()) continue;
            String allowedClean = normalizeTitle(allowed);
            String wordRegex = "\\b" + java.util.regex.Pattern.quote(allowedClean) + "\\b";
            if (java.util.regex.Pattern.compile(wordRegex).matcher(jobTitleClean).find()) {
                return true;
            }
            String[] tokens = allowedClean.split("\\s+");
            if (tokens.length == 2) {
                String regex = "\\b" + java.util.regex.Pattern.quote(tokens[0]) + "(?:\\s+\\w+)?\\s+" + java.util.regex.Pattern.quote(tokens[1]) + "\\b";
                if (java.util.regex.Pattern.compile(regex).matcher(jobTitleClean).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalizeTitle(String title) {
        if (title == null) return "";
        return title.toLowerCase(Locale.ROOT)
                .replace("-", " ")
                .replace("/", " ")
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void checkLocation(JobRecord job, PreferenceSetRecord prefs,
                               List<HardFilterResult.FilterReason> reasons, boolean strict) {
        List<String> allowed = prefs.locationsAllowed();
        if (allowed == null || allowed.isEmpty()) return;

        String city = job.city();
        String country = job.country();
        String locationRaw = job.locationRaw();

        if (isBlank(city) && isBlank(country) && isBlank(locationRaw)) {
            if (strict) {
                reasons.add(new HardFilterResult.FilterReason(
                        "LOCATION",
                        String.join(", ", allowed),
                        "unknown",
                        "Job location is missing; strict auto-apply will not assume location eligibility."
                ));
            }
            return;
        }

        boolean matched = matchesLocation(job, allowed);

        if (!matched) {
            String jobLocation = buildJobLocationString(city, country, locationRaw);
            reasons.add(new HardFilterResult.FilterReason(
                    "LOCATION",
                    String.join(", ", allowed),
                    jobLocation,
                    "Job location '" + jobLocation + "' is not in your allowed locations."
            ));
        }
    }

    private void checkAllowedCountries(JobRecord job, List<String> allowedCountries,
                                       List<HardFilterResult.FilterReason> reasons, boolean strict) {
        if (allowedCountries == null || allowedCountries.isEmpty()) return;
        String city = job.city();
        String country = job.country();
        String locationRaw = job.locationRaw();
        if (isBlank(city) && isBlank(country) && isBlank(locationRaw)) {
            if (strict) {
                reasons.add(new HardFilterResult.FilterReason(
                        "COUNTRY",
                        String.join(", ", allowedCountries),
                        "unknown",
                        "Job country is missing; strict auto-apply requires a verified allowed country."
                ));
            }
            return;
        }
        if (!matchesLocation(job, allowedCountries)) {
            String jobLocation = buildJobLocationString(city, country, locationRaw);
            reasons.add(new HardFilterResult.FilterReason(
                    "COUNTRY",
                    String.join(", ", allowedCountries),
                    jobLocation,
                    "Job location '" + jobLocation + "' is outside allowed countries."
            ));
        }
    }

    public static boolean matchesLocation(JobRecord job, List<String> allowedLocations) {
        if (allowedLocations == null || allowedLocations.isEmpty()) return true;

        String city = job.city();
        String country = job.country();
        String locationRaw = job.locationRaw();

        if (isBlank(city) && isBlank(country) && isBlank(locationRaw)) return true;

        boolean hasUkPref = allowedLocations.stream()
                .anyMatch(l -> l != null && UK_SYNONYMS.contains(l.trim().toLowerCase(Locale.ROOT)));

        if (hasUkPref && isUkLocation(city, country, locationRaw, job.remoteType())) {
            return true;
        }

        return allowedLocations.stream().anyMatch(loc -> {
            if (loc == null || loc.isBlank()) return false;
            String locLower = loc.toLowerCase(Locale.ROOT).trim();
            return equalsIgnoreCase(locLower, city)
                    || equalsIgnoreCase(locLower, country)
                    || containsIgnoreCase(locationRaw, locLower);
        });
    }

    private static boolean isUkLocation(String city, String country, String locationRaw, String remoteType) {
        if (country != null) {
            String c = country.trim().toLowerCase(Locale.ROOT);
            if (UK_SYNONYMS.contains(c)) return true;
        }
        if (city != null && UK_PATTERN.matcher(city).find()) {
            return true;
        }
        if (locationRaw != null && UK_PATTERN.matcher(locationRaw).find()) {
            return true;
        }
        if ("REMOTE".equalsIgnoreCase(remoteType) && (country == null || country.isBlank() || UK_SYNONYMS.contains(country.trim().toLowerCase(Locale.ROOT)))) {
            if (locationRaw == null || locationRaw.isBlank() || locationRaw.equalsIgnoreCase("remote")) {
                return true;
            }
            if (locationRaw.toLowerCase(Locale.ROOT).contains("uk") || locationRaw.toLowerCase(Locale.ROOT).contains("united kingdom")) {
                return true;
            }
        }
        return false;
    }

    private void checkRemoteType(JobRecord job, PreferenceSetRecord prefs,
                                 List<HardFilterResult.FilterReason> reasons, boolean strict) {
        List<String> allowedRemote = prefs.remoteTypes();
        if (allowedRemote == null || allowedRemote.isEmpty()) return;

        String jobRemote = job.remoteType();
        if (isBlank(jobRemote) || "UNKNOWN".equalsIgnoreCase(jobRemote)) {
            if (strict) {
                reasons.add(new HardFilterResult.FilterReason(
                        "REMOTE_TYPE",
                        String.join(", ", allowedRemote),
                        isBlank(jobRemote) ? "unknown" : jobRemote,
                        "Job workplace type is missing; strict auto-apply requires confirmed workplace compatibility."
                ));
            }
            return;
        }

        boolean matched = allowedRemote.stream()
                .anyMatch(r -> r.equalsIgnoreCase(jobRemote));

        if (!matched) {
            reasons.add(new HardFilterResult.FilterReason(
                    "REMOTE_TYPE",
                    String.join(", ", allowedRemote),
                    jobRemote,
                    "Job workplace type '" + jobRemote + "' does not match your preference."
            ));
        }
    }

    private void checkEmploymentType(JobRecord job, PreferenceSetRecord prefs,
                                     List<HardFilterResult.FilterReason> reasons, boolean strict) {
        List<String> allowedTypes = prefs.employmentTypes();
        if (allowedTypes == null || allowedTypes.isEmpty()) return;

        String jobType = job.employmentType();
        if (isBlank(jobType)) {
            if (strict) {
                reasons.add(new HardFilterResult.FilterReason(
                        "EMPLOYMENT_TYPE",
                        String.join(", ", allowedTypes),
                        "unknown",
                        "Job employment type is missing; strict auto-apply requires confirmed employment type."
                ));
            }
            return;
        }

        boolean matched = allowedTypes.stream()
                .anyMatch(t -> t.equalsIgnoreCase(jobType));

        if (!matched) {
            reasons.add(new HardFilterResult.FilterReason(
                    "EMPLOYMENT_TYPE",
                    String.join(", ", allowedTypes),
                    jobType,
                    "Job employment type '" + jobType + "' does not match your preference."
            ));
        }
    }

    private void checkExperienceLevel(JobRecord job, PreferenceSetRecord prefs,
                                      List<HardFilterResult.FilterReason> reasons, boolean strict) {
        List<String> allowedLevels = prefs.experienceLevels();
        if (allowedLevels == null || allowedLevels.isEmpty()) return;

        String jobLevel = job.experienceLevel();
        if (isBlank(jobLevel)) {
            if (strict) {
                reasons.add(new HardFilterResult.FilterReason(
                        "EXPERIENCE_LEVEL",
                        String.join(", ", allowedLevels),
                        "unknown",
                        "Job experience level is missing; strict auto-apply requires confirmed seniority fit."
                ));
            }
            return;
        }

        boolean matched = allowedLevels.stream()
                .anyMatch(l -> l.equalsIgnoreCase(jobLevel));

        if (!matched) {
            reasons.add(new HardFilterResult.FilterReason(
                    "EXPERIENCE_LEVEL",
                    String.join(", ", allowedLevels),
                    jobLevel,
                    "Job experience level '" + jobLevel + "' does not match your preference."
            ));
        }
    }

    public static boolean matchesSalary(JobRecord job, Long salaryMinGbp) {
        if (salaryMinGbp == null || salaryMinGbp <= 0) return true;
        BigDecimal jobMax = job.salaryMax();
        if (jobMax == null) return true;
        return jobMax.compareTo(BigDecimal.valueOf(salaryMinGbp)) >= 0;
    }

    private void checkSalaryMin(JobRecord job, PreferenceSetRecord prefs,
                                List<HardFilterResult.FilterReason> reasons, boolean strict) {
        Long minSalary = prefs.salaryMinGbp();
        if (minSalary == null) return;

        BigDecimal jobMax = job.salaryMax();
        BigDecimal jobMin = job.salaryMin();
        if (jobMax == null && (!strict || jobMin == null)) {
            if (strict) {
                reasons.add(new HardFilterResult.FilterReason(
                        "SALARY_MIN",
                        ">= " + minSalary + " GBP",
                        "unknown",
                        "Job declares no salary; strict auto-apply requires salary >= " + minSalary + " GBP."
                ));
            }
            return;
        }

        BigDecimal upperBound = jobMax != null ? jobMax : jobMin;
        if (upperBound.compareTo(BigDecimal.valueOf(minSalary)) < 0) {
            reasons.add(new HardFilterResult.FilterReason(
                    "SALARY_MIN",
                    ">= " + minSalary + " GBP",
                    upperBound + " GBP",
                    "Job salary (" + upperBound + " GBP) is below your minimum (" + minSalary + " GBP)."
            ));
        }
    }

    private void checkSponsorshipPolicy(JobRecord job, PreferenceSetRecord prefs,
                                        List<HardFilterResult.FilterReason> reasons, boolean strict) {
        String policy = prefs.sponsorshipPolicy();
        if (isBlank(policy) || "NONE".equalsIgnoreCase(policy)) return;

        // "REQUIRED" / "SPONSORSHIP_REQUIRED" means the user needs sponsorship.
        if (!"REQUIRED".equalsIgnoreCase(policy) && !"SPONSORSHIP_REQUIRED".equalsIgnoreCase(policy)) return;

        UUID companyId = job.companyId();
        if (companyId == null) {
            if (strict) {
                reasons.add(new HardFilterResult.FilterReason(
                        "SPONSORSHIP",
                        "Confirmed sponsor licence",
                        "unknown company",
                        "Visa sponsorship is required, but this employer is unverified for strict auto-apply."
                ));
            }
            return;
        }

        List<Map<String, Object>> sponsorRows = db.queryForList(
                """
                select licence_indicator, confidence
                from sponsor_records
                where company_id = ?
                order by fetched_at desc
                limit 1
                """, companyId);

        if (sponsorRows.isEmpty()) {
            if (strict) {
                reasons.add(new HardFilterResult.FilterReason(
                        "SPONSORSHIP",
                        "Confirmed sponsor licence",
                        "no record",
                        "Visa sponsorship is required, but no sponsor licence record exists for this company."
                ));
            }
            return;
        }

        String indicator = (String) sponsorRows.get(0).get("licence_indicator");
        if (indicator != null && "NO_LICENCE".equalsIgnoreCase(indicator)) {
            reasons.add(new HardFilterResult.FilterReason(
                    "SPONSORSHIP",
                    "Sponsorship required",
                    "Company does not hold a sponsor licence",
                    "You require visa sponsorship, but this company is recorded as not holding a sponsor licence."
            ));
        } else if (strict && (isBlank(indicator) || "UNKNOWN".equalsIgnoreCase(indicator) || "UNVERIFIED".equalsIgnoreCase(indicator))) {
            reasons.add(new HardFilterResult.FilterReason(
                    "SPONSORSHIP",
                    "Confirmed sponsor licence",
                    isBlank(indicator) ? "unknown" : indicator,
                    "Visa sponsorship is required, but company sponsorship indicator is unconfirmed."
            ));
        }
    }

    private void checkCompanyFilters(JobRecord job, PreferenceSetRecord prefs,
                                     List<HardFilterResult.FilterReason> reasons) {
        List<String> sizePref = prefs.companySizePref();
        List<String> industryPref = prefs.industryPref();

        boolean hasSize = sizePref != null && !sizePref.isEmpty();
        boolean hasIndustry = industryPref != null && !industryPref.isEmpty();
        if (!hasSize && !hasIndustry) return;

        UUID companyId = job.companyId();
        if (companyId == null) return; // no company link → pass

        List<Map<String, Object>> companyRows = db.queryForList(
                "select size_bucket, industry from companies where id = ?", companyId);
        if (companyRows.isEmpty()) return;

        Map<String, Object> company = companyRows.get(0);

        if (hasSize) {
            String sizeBucket = (String) company.get("size_bucket");
            if (!isBlank(sizeBucket)) {
                boolean matched = sizePref.stream()
                        .anyMatch(s -> s.equalsIgnoreCase(sizeBucket));
                if (!matched) {
                    reasons.add(new HardFilterResult.FilterReason(
                            "COMPANY_SIZE",
                            String.join(", ", sizePref),
                            sizeBucket,
                            "Company size '" + sizeBucket + "' does not match your preference."
                    ));
                }
            }
        }

        if (hasIndustry) {
            String industry = (String) company.get("industry");
            if (!isBlank(industry)) {
                boolean matched = industryPref.stream()
                        .anyMatch(i -> i.equalsIgnoreCase(industry));
                if (!matched) {
                    reasons.add(new HardFilterResult.FilterReason(
                            "INDUSTRY",
                            String.join(", ", industryPref),
                            industry,
                            "Company industry '" + industry + "' does not match your preference."
                    ));
                }
            }
        }
    }

    private void checkExcludedKeywords(JobRecord job, PreferenceSetRecord prefs,
                                       List<HardFilterResult.FilterReason> reasons) {
        List<String> excluded = prefs.keywordsExclude();
        if (excluded == null || excluded.isEmpty()) return;

        String title = job.title() == null ? "" : job.title().toLowerCase(Locale.ROOT);
        String description = job.descriptionText() == null ? "" : job.descriptionText().toLowerCase(Locale.ROOT);
        String combined = title + " " + description;

        List<String> hits = excluded.stream()
                .filter(kw -> combined.contains(kw.toLowerCase(Locale.ROOT)))
                .toList();

        if (!hits.isEmpty()) {
            reasons.add(new HardFilterResult.FilterReason(
                    "EXCLUDED_KEYWORD",
                    "Exclude: " + String.join(", ", excluded),
                    "Found: " + String.join(", ", hits),
                    "Job contains excluded keyword(s): " + String.join(", ", hits) + "."
            ));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String buildJobLocationString(String city, String country, String locationRaw) {
        if (!isBlank(city) && !isBlank(country)) return city + ", " + country;
        if (!isBlank(city)) return city;
        if (!isBlank(country)) return country;
        if (!isBlank(locationRaw)) return locationRaw;
        return "unknown";
    }
}
