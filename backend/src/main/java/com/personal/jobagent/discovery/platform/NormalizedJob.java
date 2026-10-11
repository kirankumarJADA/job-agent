package com.personal.jobagent.discovery.platform;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A posting as an adapter understood it. Every field the source did not state
 * stays null — adapters never infer salaries, dates, employers, locations or
 * sponsorship. {@link Normalize} holds the shared, conservative conversions.
 *
 * @param externalId     the platform's own stable job identifier
 * @param sourceUrl      where the posting was found (provider job page; provenance only)
 * @param applicationUrl the EMPLOYER's application page; never an aggregator URL
 * @param remoteType     REMOTE / HYBRID / ONSITE, or null when not stated
 * @param salaryCurrency ISO-4217 code, or null; salary bounds are dropped when the currency is unknown
 * @param active         false only when the source explicitly marks the posting closed
 */
public record NormalizedJob(
        String externalId,
        String title,
        String employerName,
        String locationRaw,
        String city,
        String country,
        String remoteType,
        String employmentType,
        String descriptionText,
        String applicationUrl,
        String sourceUrl,
        Instant postedAt,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String salaryCurrency,
        String salaryPeriod,
        String requisitionId,
        List<String> skills,
        Boolean active) {

    public NormalizedJob {
        skills = skills == null ? List.of() : List.copyOf(skills);
        if (salaryMin == null && salaryMax == null) salaryCurrency = null; // a currency alone is not a salary
        if (salaryCurrency == null) {
            // A bare number without its currency is not a salary we can state.
            salaryMin = null;
            salaryMax = null;
            salaryPeriod = null;
        }
    }

    public static Builder builder(String externalId, String title) {
        return new Builder(externalId, title);
    }

    public Builder toBuilder() {
        Builder b = new Builder(externalId, title);
        b.employerName = employerName; b.locationRaw = locationRaw; b.city = city; b.country = country;
        b.remoteType = remoteType; b.employmentType = employmentType; b.descriptionText = descriptionText;
        b.applicationUrl = applicationUrl; b.sourceUrl = sourceUrl; b.postedAt = postedAt;
        b.salaryMin = salaryMin; b.salaryMax = salaryMax; b.salaryCurrency = salaryCurrency;
        b.salaryPeriod = salaryPeriod; b.requisitionId = requisitionId; b.skills = skills; b.active = active;
        return b;
    }

    /** Mutable assembly helper so adapters only set what the source actually provided. */
    public static final class Builder {
        private final String externalId;
        private String title;
        private String employerName, locationRaw, city, country, remoteType, employmentType, descriptionText;
        private String applicationUrl, sourceUrl, salaryCurrency, salaryPeriod, requisitionId;
        private Instant postedAt;
        private BigDecimal salaryMin, salaryMax;
        private List<String> skills = List.of();
        private Boolean active;

        private Builder(String externalId, String title) {
            this.externalId = externalId;
            this.title = title;
        }

        public Builder title(String v) { if (Normalize.blankToNull(v) != null) title = v; return this; }
        public Builder employer(String v) { employerName = Normalize.blankToNull(v); return this; }
        public Builder location(String v) { locationRaw = Normalize.blankToNull(v); return this; }
        public Builder city(String v) { city = Normalize.placeholderToNull(v); return this; }
        public Builder country(String v) { country = Normalize.placeholderToNull(v); return this; }
        public Builder remote(String v) { remoteType = v; return this; }
        public Builder employmentType(String v) { employmentType = Normalize.blankToNull(v); return this; }
        public Builder description(String v) { descriptionText = v; return this; }
        public Builder applicationUrl(String v) { applicationUrl = Normalize.httpUrlOrNull(v); return this; }
        public Builder sourceUrl(String v) { sourceUrl = Normalize.httpUrlOrNull(v); return this; }
        public Builder postedAt(Instant v) { postedAt = v; return this; }
        public Builder salary(BigDecimal min, BigDecimal max, String currency, String period) {
            salaryMin = min; salaryMax = max; salaryCurrency = Normalize.currencyOrNull(currency);
            salaryPeriod = Normalize.blankToNull(period); return this;
        }
        public Builder requisitionId(String v) { requisitionId = Normalize.blankToNull(v); return this; }
        public Builder skills(List<String> v) { skills = v == null ? List.of() : v; return this; }
        public Builder active(Boolean v) { active = v; return this; }

        public NormalizedJob build() {
            return new NormalizedJob(externalId, Normalize.blankToNull(title), employerName, locationRaw, city, country,
                    remoteType, employmentType, descriptionText, applicationUrl, sourceUrl, postedAt,
                    salaryMin, salaryMax, salaryCurrency, salaryPeriod, requisitionId, skills, active);
        }
    }
}
