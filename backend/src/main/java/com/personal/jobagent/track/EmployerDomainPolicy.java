package com.personal.jobagent.track;

import com.personal.jobagent.discovery.platform.JobIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stage 3I (Phase 0): Server-owned employer-domain binding for email receipts.
 *
 * <p>A DKIM-passing email may only be treated as a {@code VERIFIED} employer
 * application acknowledgement when its authenticated signing/From domains bind
 * to the server-owned application target (never via inbound email claims):
 * <ul>
 *   <li>the employer's corporate registrable domain derived from the server-owned
 *       job / plan URL (respecting multi-label public suffixes such as
 *       {@code .co.uk} and preventing wildcard/shared-hosting tenant collisions),</li>
 *   <li>a vetted ATS notification domain for a recognised third-party ATS URL
 *       (e.g., {@code greenhouse.io} / {@code greenhouse-mail.io} for
 *       {@code boards.greenhouse.io}), or</li>
 *   <li>an explicit server-configured tenant or domain mapping
 *       ({@code app.track.vetted-sender-mappings}).</li>
 * </ul>
 * Unknown or ambiguous target domains fail closed.
 */
@Component
public class EmployerDomainPolicy {

    private static final Pattern VALID_HOST = Pattern.compile(
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

    private static final Set<String> TWO_LABEL_PUBLIC_SUFFIXES = Set.of(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk",
            "co.jp", "or.jp", "ne.jp", "ac.jp",
            "com.au", "net.au", "org.au", "edu.au", "gov.au",
            "co.nz", "org.nz", "co.za", "com.br", "com.cn",
            "co.in", "org.in", "net.in", "com.sg", "com.hk",
            "co.kr", "com.mx", "com.tr", "com.ar", "com.tw",
            "com.pl", "co.il", "com.ua", "com.sa", "com.ae"
    );

    private static final Set<String> MULTI_TENANT_HOSTING_SUFFIXES = Set.of(
            "github.io", "gitlab.io", "herokuapp.com", "netlify.app", "vercel.app",
            "pages.dev", "workers.dev", "appspot.com", "cloudfront.net", "amazonaws.com",
            "azurewebsites.net", "web.app", "firebaseapp.com", "fly.dev", "onrender.com",
            "typeform.com", "tally.so", "jotform.com", "google.com"
    );

    private static final Map<String, Set<String>> VETTED_ATS_DOMAINS = Map.ofEntries(
            Map.entry("greenhouse", Set.of("greenhouse.io", "greenhouse-mail.io")),
            Map.entry("ashby", Set.of("ashbyhq.com")),
            Map.entry("lever", Set.of("lever.co")),
            Map.entry("workday", Set.of("myworkdayjobs.com", "myworkday.com")),
            Map.entry("smartrecruiters", Set.of("smartrecruiters.com", "smartrecruitersmail.com")),
            Map.entry("workable", Set.of("workable.com", "workablemail.com")),
            Map.entry("breezyhr", Set.of("breezy.hr", "breezy-mail.com")),
            Map.entry("icims", Set.of("icims.com")),
            Map.entry("pinpoint", Set.of("pinpointhq.com")),
            Map.entry("jobvite", Set.of("jobvite.com")),
            Map.entry("teamtailor", Set.of("teamtailor.com", "teamtailor-mail.com")),
            Map.entry("recruitee", Set.of("recruitee.com", "recruiteemail.com")),
            Map.entry("rippling", Set.of("rippling.com")),
            Map.entry("jazzhr", Set.of("applytojob.com", "jazzhr.com")),
            Map.entry("gem", Set.of("gem.com")),
            Map.entry("zoho_recruit", Set.of("zohorecruit.com", "zohorecruit.eu")),
            Map.entry("oracle_cloud", Set.of("oraclecloud.com")),
            Map.entry("paylocity", Set.of("paylocity.com")),
            Map.entry("ultipro", Set.of("ultipro.com")),
            Map.entry("phenom", Set.of("phenompro.com", "phenom.com")),
            Map.entry("join", Set.of("join.com"))
    );

    public enum BindingStatus {
        BOUND,
        UNBOUND_MISMATCH,
        UNBOUND_UNKNOWN
    }

    public record ApplicationTarget(
            String applicationUrl,
            String canonicalUrl,
            String company,
            List<String> planTargetUrls
    ) {
        public ApplicationTarget {
            planTargetUrls = planTargetUrls == null ? List.of() : List.copyOf(planTargetUrls);
        }

        public static ApplicationTarget ofUrls(String applicationUrl, String canonicalUrl, String company,
                                               List<String> planTargetUrls) {
            return new ApplicationTarget(applicationUrl, canonicalUrl, company, planTargetUrls);
        }
    }

    public record DomainBinding(BindingStatus status, String reasonCode, String matchedDomain) {
        public boolean isBound() {
            return status == BindingStatus.BOUND;
        }

        public static DomainBinding bound(String matchedDomain) {
            return new DomainBinding(BindingStatus.BOUND, "EMPLOYER_DOMAIN_BOUND", matchedDomain);
        }

        public static DomainBinding mismatch(String reasonCode) {
            return new DomainBinding(BindingStatus.UNBOUND_MISMATCH,
                    reasonCode == null || reasonCode.isBlank() ? "SENDER_DOMAIN_NOT_BOUND_TO_EMPLOYER" : reasonCode,
                    null);
        }

        public static DomainBinding unknown() {
            return new DomainBinding(BindingStatus.UNBOUND_UNKNOWN, "EMPLOYER_TARGET_DOMAIN_UNKNOWN", null);
        }
    }

    private final Map<String, Set<String>> vettedSenderMappings;

    public EmployerDomainPolicy() {
        this("");
    }

    @Autowired
    public EmployerDomainPolicy(@Value("${app.track.vetted-sender-mappings:}") String vettedSenderMappingsRaw) {
        this.vettedSenderMappings = parseMappings(vettedSenderMappingsRaw);
    }

    /**
     * Evaluate whether the authenticated email's {@code fromDomain} and {@code dkimDomain}
     * are legitimately bound to the application's server-owned target URLs.
     */
    public DomainBinding evaluate(ApplicationTarget target, String fromDomain, String dkimDomain) {
        if (target == null) {
            return DomainBinding.unknown();
        }
        List<String> urls = collectDistinctUrls(target);
        if (urls.isEmpty()) {
            return DomainBinding.unknown();
        }

        Set<String> allowedDomains = new LinkedHashSet<>();
        boolean recognizedTarget = false;

        for (String url : urls) {
            TargetAnalysis analyzed = analyzeTargetUrl(url);
            if (!analyzed.recognized()) {
                continue;
            }
            recognizedTarget = true;
            allowedDomains.addAll(analyzed.allowedDomains());
        }

        if (!recognizedTarget || allowedDomains.isEmpty()) {
            return DomainBinding.unknown();
        }

        String from = normalizeDomain(fromDomain);
        String dkim = normalizeDomain(dkimDomain);
        if (from == null || dkim == null) {
            return DomainBinding.mismatch("SENDER_DOMAIN_NOT_BOUND_TO_EMPLOYER");
        }

        for (String allowed : allowedDomains) {
            if (matchesAllowedDomain(from, allowed) && matchesAllowedDomain(dkim, allowed)) {
                return DomainBinding.bound(allowed);
            }
        }
        return DomainBinding.mismatch("SENDER_DOMAIN_NOT_BOUND_TO_EMPLOYER");
    }

    private record TargetAnalysis(boolean recognized, Set<String> allowedDomains) {}

    private TargetAnalysis analyzeTargetUrl(String url) {
        String host = parseHost(url);
        if (host == null) {
            return new TargetAnalysis(false, Set.of());
        }

        Set<String> allowed = new LinkedHashSet<>();
        String atsPlatform = detectAtsPlatform(host);
        if (atsPlatform != null) {
            Set<String> platformDomains = VETTED_ATS_DOMAINS.get(atsPlatform);
            if (platformDomains != null) {
                allowed.addAll(platformDomains);
            }
            String tenantKey = atsPlatform + ":" + extractAtsTenant(atsPlatform, host, parsePath(url));
            addConfiguredMappings(tenantKey, allowed);
            addConfiguredMappings(host, allowed);
            return new TargetAnalysis(!allowed.isEmpty(), Set.copyOf(allowed));
        }

        if (JobIdentity.isJobBoardUrl(url)) {
            addConfiguredMappings(host, allowed);
            return new TargetAnalysis(!allowed.isEmpty(), Set.copyOf(allowed));
        }

        // Direct corporate career host
        String corpDomain = registrableDomain(host);
        if (corpDomain == null) {
            if ("localhost".equals(host)) {
                allowed.add("localhost");
                addConfiguredMappings(host, allowed);
                return new TargetAnalysis(true, Set.copyOf(allowed));
            }
            return new TargetAnalysis(false, Set.of());
        }
        allowed.add(corpDomain);
        addConfiguredMappings(corpDomain, allowed);
        addConfiguredMappings(host, allowed);
        return new TargetAnalysis(true, Set.copyOf(allowed));
    }

    private void addConfiguredMappings(String key, Set<String> out) {
        if (key == null || key.isBlank()) return;
        Set<String> mapped = vettedSenderMappings.get(key.toLowerCase(Locale.ROOT));
        if (mapped != null) {
            out.addAll(mapped);
        }
    }

    static boolean matchesAllowedDomain(String candidate, String allowed) {
        if (candidate == null || allowed == null) return false;
        if ("localhost".equals(allowed)) return "localhost".equals(candidate);
        if (isMultiTenantHostOrSubdomain(allowed)) {
            return candidate.equals(allowed);
        }
        return candidate.equals(allowed) || candidate.endsWith("." + allowed);
    }

    static String registrableDomain(String host) {
        String clean = normalizeDomain(host);
        if (clean == null) return null;
        if (MULTI_TENANT_HOSTING_SUFFIXES.contains(clean)) return null;
        for (String suffix : MULTI_TENANT_HOSTING_SUFFIXES) {
            if (clean.endsWith("." + suffix)) {
                return clean;
            }
        }
        for (String psl : TWO_LABEL_PUBLIC_SUFFIXES) {
            if (clean.equals(psl)) {
                return null;
            }
            if (clean.endsWith("." + psl)) {
                String[] parts = clean.split("\\.");
                if (parts.length < 3) return null;
                return parts[parts.length - 3] + "." + parts[parts.length - 2] + "." + parts[parts.length - 1];
            }
        }
        String[] parts = clean.split("\\.");
        if (parts.length < 2) return null;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static boolean isMultiTenantHostOrSubdomain(String host) {
        for (String suffix : MULTI_TENANT_HOSTING_SUFFIXES) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String detectAtsPlatform(String host) {
        if (host.equals("greenhouse.io") || host.endsWith(".greenhouse.io")) return "greenhouse";
        if (host.equals("ashbyhq.com") || host.endsWith(".ashbyhq.com")) return "ashby";
        if (host.equals("lever.co") || host.endsWith(".lever.co")) return "lever";
        if (host.endsWith(".myworkdayjobs.com") || host.equals("myworkdayjobs.com")
                || host.endsWith(".myworkday.com") || host.equals("myworkday.com")) return "workday";
        if (host.equals("smartrecruiters.com") || host.endsWith(".smartrecruiters.com")) return "smartrecruiters";
        if (host.equals("workable.com") || host.endsWith(".workable.com")) return "workable";
        if (host.equals("breezy.hr") || host.endsWith(".breezy.hr")) return "breezyhr";
        if (host.equals("icims.com") || host.endsWith(".icims.com")) return "icims";
        if (host.equals("pinpointhq.com") || host.endsWith(".pinpointhq.com")) return "pinpoint";
        if (host.equals("jobvite.com") || host.endsWith(".jobvite.com")) return "jobvite";
        if (host.equals("teamtailor.com") || host.endsWith(".teamtailor.com")) return "teamtailor";
        if (host.equals("recruitee.com") || host.endsWith(".recruitee.com")) return "recruitee";
        if (host.equals("rippling.com") || host.endsWith(".rippling.com")) return "rippling";
        if (host.equals("applytojob.com") || host.endsWith(".applytojob.com")
                || host.equals("jazzhr.com") || host.endsWith(".jazzhr.com")) return "jazzhr";
        if (host.equals("gem.com") || host.endsWith(".gem.com")) return "gem";
        if (host.equals("zohorecruit.com") || host.endsWith(".zohorecruit.com")
                || host.equals("zohorecruit.eu") || host.endsWith(".zohorecruit.eu")) return "zoho_recruit";
        if (host.equals("oraclecloud.com") || host.endsWith(".oraclecloud.com")) return "oracle_cloud";
        if (host.equals("paylocity.com") || host.endsWith(".paylocity.com")) return "paylocity";
        if (host.equals("ultipro.com") || host.endsWith(".ultipro.com")) return "ultipro";
        if (host.equals("phenompro.com") || host.endsWith(".phenompro.com")
                || host.equals("phenom.com") || host.endsWith(".phenom.com")) return "phenom";
        if (host.equals("join.com") || host.endsWith(".join.com")) return "join";
        return null;
    }

    private static String extractAtsTenant(String platform, String host, String path) {
        if ("workday".equals(platform)) {
            int dot = host.indexOf('.');
            return dot > 0 ? host.substring(0, dot) : "";
        }
        String trimmedPath = path == null ? "" : path.replaceAll("^/+", "");
        int slash = trimmedPath.indexOf('/');
        String firstSegment = slash >= 0 ? trimmedPath.substring(0, slash) : trimmedPath;
        if (!firstSegment.isBlank() && !"jobs".equals(firstSegment) && !"apply".equals(firstSegment)) {
            return firstSegment.toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private static List<String> collectDistinctUrls(ApplicationTarget target) {
        Set<String> urls = new LinkedHashSet<>();
        for (String u : target.planTargetUrls()) {
            if (u != null && !u.isBlank()) urls.add(u.trim());
        }
        if (target.canonicalUrl() != null && !target.canonicalUrl().isBlank()) {
            urls.add(target.canonicalUrl().trim());
        }
        if (target.applicationUrl() != null && !target.applicationUrl().isBlank()) {
            urls.add(target.applicationUrl().trim());
        }
        return new ArrayList<>(urls);
    }

    private static String parseHost(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
                return null;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) return null;
            String clean = host.trim().toLowerCase(Locale.ROOT);
            while (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1);
            if ("localhost".equals(clean)) return "localhost";
            if (IPV4.matcher(clean).matches()) return null;
            return VALID_HOST.matcher(clean).matches() ? clean : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String parsePath(String url) {
        try {
            return URI.create(url.trim()).getPath();
        } catch (Exception e) {
            return "";
        }
    }

    static String normalizeDomain(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        while (clean.startsWith("@")) clean = clean.substring(1);
        while (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1);
        if (clean.contains("*") || clean.contains("/") || clean.contains(" ")) return null;
        if ("localhost".equals(clean)) return "localhost";
        if (IPV4.matcher(clean).matches()) return null;
        return VALID_HOST.matcher(clean).matches() ? clean : null;
    }

    private static Map<String, Set<String>> parseMappings(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (String rule : raw.split(";")) {
            String trimmed = rule.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) continue;
            String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String rhs = trimmed.substring(eq + 1).trim();
            Set<String> targets = new LinkedHashSet<>();
            for (String domain : rhs.split(",")) {
                String norm = normalizeDomain(domain);
                if (norm != null) {
                    targets.add(norm);
                }
            }
            if (!key.isBlank() && !targets.isEmpty()) {
                map.merge(key, Set.copyOf(targets), (a, b) -> {
                    Set<String> combined = new LinkedHashSet<>(a);
                    combined.addAll(b);
                    return Set.copyOf(combined);
                });
            }
        }
        return Map.copyOf(map);
    }
}
