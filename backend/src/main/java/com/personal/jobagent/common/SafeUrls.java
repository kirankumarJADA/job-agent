package com.personal.jobagent.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Stage 3F (Workstream E): one place for URL handling that must not leak or be fooled.
 *
 * <ul>
 *   <li>{@link #redactForLog} — what a log line or audit row may contain:
 *       scheme, host, port and path only. Never userinfo, query strings
 *       (tokens, emails, session ids) or fragments.</li>
 *   <li>{@link #canonicalHost} / {@link #hostIsOrIsSubdomainOf} — host-boundary
 *       matching on the parsed host, never substring matching on the URL text
 *       (so {@code https://evil.example/?q=greenhouse.io} and
 *       {@code https://greenhouse.io.evil.example} are not "greenhouse.io").</li>
 * </ul>
 */
public final class SafeUrls {

    private static final int MAX_PATH_CHARS = 200;

    private SafeUrls() {}

    public static String redactForLog(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            URI uri = new URI(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return "[unparseable-url]";
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (path.length() > MAX_PATH_CHARS) path = path.substring(0, MAX_PATH_CHARS) + "...";
            String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + port + path;
        } catch (URISyntaxException e) {
            return "[unparseable-url]";
        }
    }

    /** Lower-cased host with trailing dots removed; empty for anything without a host. */
    public static Optional<String> canonicalHost(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        try {
            String host = new URI(url.trim()).getHost();
            if (host == null) return Optional.empty();
            host = host.toLowerCase(Locale.ROOT);
            while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            return host.isEmpty() ? Optional.empty() : Optional.of(host);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** True when {@code host} equals {@code domain} or is a subdomain of it, on a label boundary. */
    public static boolean hostIsOrIsSubdomainOf(String host, String domain) {
        if (host == null || domain == null || host.isBlank() || domain.isBlank()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        String d = domain.toLowerCase(Locale.ROOT);
        return h.equals(d) || h.endsWith("." + d);
    }
}
