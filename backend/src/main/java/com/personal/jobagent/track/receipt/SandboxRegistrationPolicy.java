package com.personal.jobagent.track.receipt;

import com.personal.jobagent.discovery.platform.AddressGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Server-controlled policy for candidate-owned sandbox registrations ({@code candidate_sandbox_registrations}).
 *
 * <p>Enforces canonical posting URL shape, HTTPS, explicit server-configured domain and path-prefix
 * allowlists, and SSRF protection (rejecting localhost, RFC 1918 private networks, link-local / cloud
 * metadata {@code 169.254.169.254}, and non-public IP resolutions unless explicitly opted in inside an
 * isolated test configuration). Derives {@code permittedDomain} strictly from server configuration.
 */
@Component
public class SandboxRegistrationPolicy {

    private static final Pattern GREENHOUSE_POSTING_PATH =
            Pattern.compile("^/[A-Za-z0-9_.-]{1,120}/jobs/[A-Za-z0-9_.-]{1,120}$");

    private static final List<String> SHARED_MULTI_TENANT_ATS_HOSTS = List.of(
            "boards.greenhouse.io",
            "job-boards.greenhouse.io",
            "jobs.ashbyhq.com",
            "jobs.lever.co"
    );

    private final List<String> allowedDomains;
    private final List<String> allowedPathPrefixes;
    private final boolean requireHttps;
    private final boolean allowLocalTestHosts;
    private final AddressGuard addressGuard;

    public record ValidatedSandboxTarget(
            String atsPlatform,
            String canonicalPostingUrl,
            String permittedDomain
    ) {}

    @Autowired
    public SandboxRegistrationPolicy(
            @Value("${app.sandbox.allowed-domains:}") String allowedDomainsCsv,
            @Value("${app.sandbox.allowed-path-prefixes:}") String allowedPathPrefixesCsv,
            @Value("${app.sandbox.require-https:true}") boolean requireHttps,
            @Value("${app.sandbox.allow-local-test-hosts:false}") boolean allowLocalTestHosts,
            @Autowired(required = false) AddressGuard addressGuard) {
        this(parseCsvLower(allowedDomainsCsv), parsePathPrefixes(allowedPathPrefixesCsv),
                requireHttps, allowLocalTestHosts, addressGuard != null ? addressGuard : new AddressGuard());
    }

    public SandboxRegistrationPolicy(
            List<String> allowedDomains,
            List<String> allowedPathPrefixes,
            boolean requireHttps,
            boolean allowLocalTestHosts,
            AddressGuard addressGuard) {
        this.allowedDomains = allowedDomains == null ? List.of() : allowedDomains.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        this.allowedPathPrefixes = allowedPathPrefixes == null ? List.of() : allowedPathPrefixes.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(SandboxRegistrationPolicy::normalizePathPrefix)
                .distinct()
                .toList();
        this.requireHttps = requireHttps;
        this.allowLocalTestHosts = allowLocalTestHosts;
        this.addressGuard = addressGuard != null ? addressGuard : new AddressGuard();
    }

    public boolean isConfigured() {
        if (allowedDomains.isEmpty()) {
            return false;
        }
        return !hasSharedMultiTenantDomainWithoutPrefixRestriction();
    }

    private boolean hasSharedMultiTenantDomainWithoutPrefixRestriction() {
        return allowedPathPrefixes.isEmpty()
                && allowedDomains.stream().anyMatch(SandboxRegistrationPolicy::isSharedMultiTenantDomain);
    }

    private static boolean isSharedMultiTenantDomain(String domain) {
        return SHARED_MULTI_TENANT_ATS_HOSTS.contains(domain)
                || domain.endsWith(".greenhouse.io")
                || "greenhouse.io".equals(domain)
                || domain.endsWith(".myworkdayjobs.com")
                || "myworkdayjobs.com".equals(domain);
    }

    public List<String> allowedDomains() {
        return allowedDomains;
    }

    public List<String> allowedPathPrefixes() {
        return allowedPathPrefixes;
    }

    public boolean requireHttps() {
        return requireHttps;
    }

    /**
     * Validates a candidate sandbox registration request and returns canonical URL + server-derived permittedDomain.
     *
     * @throws IllegalStateException when sandbox allowlist is not configured on the server
     * @throws IllegalArgumentException when the platform, URL, host, SSRF check or path violates policy
     */
    public ValidatedSandboxTarget validateAndCanonicalize(String atsPlatform, String rawPostingUrl) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Sandbox registrations are disabled because no allowed sandbox domains are configured on the server");
        }
        String platform = normalizePlatform(atsPlatform);
        URI uri = parseStrictUri(rawPostingUrl);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();

        boolean isolatedLocalHost = allowLocalTestHosts && isLoopbackHostname(host);
        if (isolatedLocalHost) {
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("Posting URL must use http or https in isolated test mode");
            }
            if (requireHttps && !scheme.equals("https")) {
                throw new IllegalArgumentException("Posting URL must use https");
            }
        } else {
            if (!scheme.equals("https")) {
                throw new IllegalArgumentException("Posting URL must use https");
            }
            if (port != -1 && port != 443) {
                throw new IllegalArgumentException("Non-standard ports are not allowed for sandbox posting URLs");
            }
        }

        // Match server-controlled allowlist and derive permittedDomain solely from configuration.
        String matchedDomain = matchAllowedDomain(host);
        if (matchedDomain == null) {
            throw new IllegalArgumentException(
                    "Posting domain is not in the server's allowlisted sandbox domains");
        }

        // Enforce SSRF protection (blocking loopback, RFC 1918 private ranges, 169.254.169.254 metadata,
        // internal suffixes, IP literals, and non-public DNS resolutions) unless explicitly isolated local test host.
        if (!isolatedLocalHost) {
            try {
                addressGuard.check(uri);
            } catch (AddressGuard.UnsafeAddressException e) {
                throw new IllegalArgumentException("Posting URL host failed SSRF safety check: " + e.getMessage());
            }
        } else {
            rejectPrivateMetadataLiterals(host);
        }

        String canonicalPath = normalizePostingPath(uri.getRawPath());
        rejectDotSegments(canonicalPath);
        validatePlatformPath(platform, canonicalPath);
        if (!allowedPathPrefixes.isEmpty() && !matchesAllowedPathPrefix(canonicalPath)) {
            throw new IllegalArgumentException(
                    "Posting URL path is not within the server's configured sandbox path prefixes");
        }

        int effectivePort = (!isolatedLocalHost
                || ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80))
                ? -1 : port;
        String canonicalUrl = buildCanonicalUrl(scheme, host, effectivePort, canonicalPath);
        return new ValidatedSandboxTarget(platform, canonicalUrl, matchedDomain);
    }

    /**
     * Canonicalizes an existing posting URL (lowercase scheme/host, default port removed, trailing slash trimmed).
     * Returns empty when input is null, blank or unparseable.
     */
    public static String canonicalizePostingUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            int effectivePort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)
                    ? -1 : port;
            String path = normalizePostingPath(uri.getRawPath());
            String base = buildCanonicalUrl(scheme, host, effectivePort, path);
            if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                return base + "?" + uri.getRawQuery();
            }
            return base;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalizePlatform(String atsPlatform) {
        if (atsPlatform == null || atsPlatform.isBlank()) {
            throw new IllegalArgumentException("atsPlatform is required");
        }
        String normalized = atsPlatform.trim().toUpperCase(Locale.ROOT);
        if (!"GREENHOUSE".equals(normalized)) {
            throw new IllegalArgumentException("Sprint 2.3 sandbox registration only supports GREENHOUSE postings");
        }
        return normalized;
    }

    private static URI parseStrictUri(String rawPostingUrl) {
        if (rawPostingUrl == null || rawPostingUrl.isBlank()) {
            throw new IllegalArgumentException("postingUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(rawPostingUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("postingUrl is not a valid URI");
        }
        if (uri.getScheme() == null || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("postingUrl must include scheme and host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("postingUrl must not contain user credentials");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("postingUrl must not contain a URL fragment");
        }
        if (uri.getRawQuery() != null) {
            throw new IllegalArgumentException("postingUrl must not contain query parameters");
        }
        return uri;
    }

    private String matchAllowedDomain(String host) {
        for (String configured : allowedDomains) {
            if (host.equals(configured) || (!isIpLiteral(host) && host.endsWith("." + configured))) {
                return configured;
            }
        }
        return null;
    }

    private boolean matchesAllowedPathPrefix(String canonicalPath) {
        String lowerPath = canonicalPath.toLowerCase(Locale.ROOT);
        for (String prefix : allowedPathPrefixes) {
            if (prefix.endsWith("/")) {
                if (lowerPath.startsWith(prefix)) {
                    return true;
                }
            } else if (lowerPath.equals(prefix) || lowerPath.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static void rejectDotSegments(String canonicalPath) {
        for (String segment : canonicalPath.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Posting URL path must not contain dot segments");
            }
        }
    }

    private static void validatePlatformPath(String platform, String canonicalPath) {
        if ("GREENHOUSE".equals(platform)) {
            if (!GREENHOUSE_POSTING_PATH.matcher(canonicalPath).matches()) {
                throw new IllegalArgumentException(
                        "Greenhouse sandbox postingUrl must match /{board}/jobs/{jobId}");
            }
        }
    }

    private static String normalizePostingPath(String rawPath) {
        String p = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String buildCanonicalUrl(String scheme, String host, int port, String canonicalPath) {
        String authority = (port == -1) ? host : (host + ":" + port);
        return scheme + "://" + authority + canonicalPath;
    }

    private static boolean isLoopbackHostname(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    }

    private static void rejectPrivateMetadataLiterals(String host) {
        if (isLoopbackHostname(host)) {
            return;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLinkLocalAddress() || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
                throw new IllegalArgumentException("Private/metadata addresses are forbidden");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported host");
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.startsWith("[") || host.matches("[0-9.]+") || host.contains(":");
    }

    private static List<String> parseCsvLower(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static List<String> parsePathPrefixes(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(SandboxRegistrationPolicy::normalizePathPrefix)
                .distinct()
                .toList();
    }

    private static String normalizePathPrefix(String p) {
        String lower = p.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("/")) {
            lower = "/" + lower;
        }
        return lower;
    }
}
