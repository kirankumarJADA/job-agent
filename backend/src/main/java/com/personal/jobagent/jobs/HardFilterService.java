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
     * result with every failing reason.
     */
    public HardFilterResult evaluate(JobRecord job, PreferenceSetRecord prefs) {
        if (prefs == null) {
            return HardFilterResult.pass();
        }

        List<HardFilterResult.FilterReason> reasons = new ArrayList<>();

        checkLocation(job, prefs, reasons);
        checkRemoteType(job, prefs, reasons);
        checkEmploymentType(job, prefs, reasons);
        checkExperienceLevel(job, prefs, reasons);
        checkSalaryMin(job, prefs, reasons);
        checkSponsorshipPolicy(job, prefs, reasons);
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

    private void checkLocation(JobRecord job, PreferenceSetRecord prefs,
                               List<HardFilterResult.FilterReason> reasons) {
        List<String> allowed = prefs.locationsAllowed();
        if (allowed == null || allowed.isEmpty()) return;

        // If job has no location data at all, pass (benefit of the doubt)
        String city = job.city();
        String country = job.country();
        String locationRaw = job.locationRaw();

        if (isBlank(city) && isBlank(country) && isBlank(locationRaw)) return;

        // Check if any allowed location matches city, country, or locationRaw
        boolean matched = allowed.stream().anyMatch(loc -> {
            String locLower = loc.toLowerCase(Locale.ROOT);
            return equalsIgnoreCase(locLower, city)
                    || equalsIgnoreCase(locLower, country)
                    || containsIgnoreCase(locationRaw, locLower);
        });

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

    private void checkRemoteType(JobRecord job, PreferenceSetRecord prefs,
                                 List<HardFilterResult.FilterReason> reasons) {
        List<String> allowedRemote = prefs.remoteTypes();
        if (allowedRemote == null || allowedRemote.isEmpty()) return;

        String jobRemote = job.remoteType();
        if (isBlank(jobRemote) || "UNKNOWN".equalsIgnoreCase(jobRemote)) return;

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
                                     List<HardFilterResult.FilterReason> reasons) {
        List<String> allowedTypes = prefs.employmentTypes();
        if (allowedTypes == null || allowedTypes.isEmpty()) return;

        String jobType = job.employmentType();
        if (isBlank(jobType)) return;

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
                                      List<HardFilterResult.FilterReason> reasons) {
        List<String> allowedLevels = prefs.experienceLevels();
        if (allowedLevels == null || allowedLevels.isEmpty()) return;

        String jobLevel = job.experienceLevel();
        if (isBlank(jobLevel)) return;

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

    private void checkSalaryMin(JobRecord job, PreferenceSetRecord prefs,
                                List<HardFilterResult.FilterReason> reasons) {
        Long minSalary = prefs.salaryMinGbp();
        if (minSalary == null) return;

        // If job declares no salary, pass (benefit of the doubt)
        BigDecimal jobMax = job.salaryMax();
        if (jobMax == null) return;

        // If the job's declared maximum is below the user's minimum, reject
        if (jobMax.compareTo(BigDecimal.valueOf(minSalary)) < 0) {
            reasons.add(new HardFilterResult.FilterReason(
                    "SALARY_MIN",
                    "≥ " + minSalary + " GBP",
                    jobMax + " GBP (max)",
                    "Job's maximum salary (" + jobMax + " GBP) is below your minimum (" + minSalary + " GBP)."
            ));
        }
    }

    private void checkSponsorshipPolicy(JobRecord job, PreferenceSetRecord prefs,
                                        List<HardFilterResult.FilterReason> reasons) {
        String policy = prefs.sponsorshipPolicy();
        if (isBlank(policy) || "NONE".equalsIgnoreCase(policy)) return;

        // "REQUIRED" means the user needs sponsorship — reject companies
        // known NOT to sponsor. We check the sponsor_records table.
        if (!"REQUIRED".equalsIgnoreCase(policy)) return;

        UUID companyId = job.companyId();
        if (companyId == null) return; // no company data → pass

        // Look for any sponsor record with positive indicator
        List<Map<String, Object>> sponsorRows = db.queryForList(
                """
                select licence_indicator, confidence
                from sponsor_records
                where company_id = ?
                order by fetched_at desc
                limit 1
                """, companyId);

        if (sponsorRows.isEmpty()) return; // no data → pass

        String indicator = (String) sponsorRows.get(0).get("licence_indicator");
        if (indicator != null && "NO_LICENCE".equalsIgnoreCase(indicator)) {
            reasons.add(new HardFilterResult.FilterReason(
                    "SPONSORSHIP",
                    "Sponsorship required",
                    "Company does not hold a sponsor licence",
                    "You require visa sponsorship, but this company is recorded as not holding a sponsor licence."
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
