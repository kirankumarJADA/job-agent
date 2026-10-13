package com.personal.jobagent.mailbox.managed;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Strict, allocation-light e-mail address handling for managed mail.
 *
 * <p>Inbound mail is attacker-controllable input, so these helpers refuse
 * rather than guess: a header that could name more than one mailbox, or whose
 * display name hides a second address, yields no address at all. The callers
 * treat "no address" as "no evidence", which fails safe.
 */
public final class MailAddresses {

    /** Practical addr-spec: dot-atom local part, LDH domain labels, alphabetic TLD. */
    private static final Pattern ADDRESS = Pattern.compile(
            "^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "@([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    private static final Pattern DOMAIN = Pattern.compile("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    private static final Pattern QUOTED = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final int MAX_HEADER = 998;

    private MailAddresses() {
    }

    /** The lower-cased address when {@code value} is exactly one bare addr-spec, else empty. */
    public static Optional<String> bareAddress(String value) {
        if (value == null) return Optional.empty();
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        if (candidate.length() > 254 || candidate.indexOf('@') <= 0 || candidate.indexOf('@') > 64) {
            return Optional.empty();
        }
        return ADDRESS.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }

    /**
     * The single mailbox in an RFC 5322 From-style header value
     * ({@code Name <a@b.example>} or {@code a@b.example}), lower-cased.
     *
     * <p>Only the bracketed address counts: a quoted display name is ignored
     * whatever it says ({@code "hr@employer.example" <x@attacker.example>} yields
     * {@code x@attacker.example}). Empty when the header lists more than one
     * mailbox, when an unquoted display name also contains an address, or when
     * the address is not well formed.
     */
    public static Optional<String> singleMailbox(String header) {
        if (header == null || header.isBlank() || header.length() > MAX_HEADER) return Optional.empty();
        // Quoted display names may legally contain brackets or commas; they are
        // never the address, so they are removed before the structure is checked.
        String unquoted = QUOTED.matcher(header).replaceAll("_");
        if (unquoted.indexOf('"') >= 0) return Optional.empty();           // unbalanced quote
        if (unquoted.indexOf(',') >= 0 || unquoted.indexOf(';') >= 0) return Optional.empty();
        int open = unquoted.indexOf('<');
        if (open < 0) {
            return unquoted.indexOf('>') >= 0 ? Optional.empty() : bareAddress(unquoted);
        }
        if (unquoted.indexOf('<', open + 1) >= 0) return Optional.empty();
        int close = unquoted.indexOf('>', open);
        if (close < 0 || unquoted.indexOf('>', close + 1) >= 0) return Optional.empty();
        String outside = unquoted.substring(0, open) + unquoted.substring(close + 1);
        if (outside.indexOf('@') >= 0) return Optional.empty();
        if (!unquoted.substring(close + 1).isBlank()) return Optional.empty();
        return bareAddress(unquoted.substring(open + 1, close));
    }

    /** Domain part of a validated address, or null. */
    public static String domainOf(String address) {
        if (address == null) return null;
        int at = address.lastIndexOf('@');
        return at < 0 ? null : address.substring(at + 1);
    }

    /** Local part of a validated address, or null. */
    public static String localPartOf(String address) {
        if (address == null) return null;
        int at = address.lastIndexOf('@');
        return at < 0 ? null : address.substring(0, at);
    }

    /**
     * Normalised employer domain: lower-case, a leading {@code www.} removed.
     * Empty when the value is not a plain domain name.
     */
    public static Optional<String> employerDomain(String value) {
        if (value == null) return Optional.empty();
        String domain = value.trim().toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
        if (domain.startsWith("www.")) domain = domain.substring(4);
        return domain.length() <= 253 && DOMAIN.matcher(domain).matches() ? Optional.of(domain) : Optional.empty();
    }

    /** True when {@code candidate} is {@code domain} itself or one of its subdomains. */
    public static boolean sameOrSubdomain(String candidate, String domain) {
        if (candidate == null || domain == null || candidate.isEmpty() || domain.isEmpty()) return false;
        String c = candidate.toLowerCase(Locale.ROOT);
        String d = domain.toLowerCase(Locale.ROOT);
        return c.equals(d) || c.endsWith("." + d);
    }
}
