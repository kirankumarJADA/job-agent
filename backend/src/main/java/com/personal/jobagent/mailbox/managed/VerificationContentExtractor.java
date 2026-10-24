package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.track.TrackText;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a sign-up verification code or link in one message, and refuses to
 * choose when the message offers more than one.
 *
 * <p>A <b>code</b> is a stand-alone 4–8 digit number placed next to code
 * language: after "verification code", "passcode", "OTP", "verify",
 * "confirm"… (forward), or before "… is your verification code" (backward),
 * with no other number in between. Four-digit years (19xx/20xx) are never
 * codes, so a "© 2026" footer cannot make a message ambiguous or be entered.
 *
 * <p>A <b>link</b> must be https, carry no credentials or unusual port, point
 * at the employer's own domain (or a subdomain of it) and look like a
 * verification link (verify, confirm, activate, validate, magic link, or a
 * token/code parameter). The worker additionally refuses any link that is not
 * same-origin with the page it is on.
 *
 * <p>Only the caller decides what "ambiguous" means across messages; here,
 * two different codes, or two different qualifying links, in one message make
 * that message ambiguous.
 */
public final class VerificationContentExtractor {

    /**
     * A stand-alone 4–8 digit run: not part of a word, date, time, decimal,
     * address, hashtag or international number, and not one group of a
     * space-separated number such as "0800 123 4567".
     */
    private static final String DIGITS =
            "(?<![\\w\\-/.:@#+])(?<!\\d\\s)(\\d{4,8})(?![\\w\\-/:@])(?![.,]\\d)(?!\\s\\d)";
    private static final String CODE_PREFIX =
            "(?:(?:verification|confirmation|security|access|login|sign[- ]?in|one[- ]?time)[ \\t]{0,3})?";
    private static final String CODE_WORD = "(?:code|passcode|pass code|otp|pin)\\b";
    private static final String KEYWORD = "\\b" + CODE_PREFIX + CODE_WORD + "|\\bverif\\w{0,10}|\\bconfirm\\w{0,8}";
    private static final Pattern FORWARD = Pattern.compile("(?i)(?:" + KEYWORD + ")[^\\d]{0,40}?" + DIGITS);
    private static final Pattern BACKWARD = Pattern.compile("(?i)" + DIGITS + "[^\\d]{0,30}?\\b" + CODE_PREFIX + CODE_WORD);
    private static final Pattern YEAR = Pattern.compile("^(19|20)\\d\\d$");
    private static final Pattern VERIFICATION_PATH = Pattern.compile(
            "(?i)(verif|confirm|activat|validat|magic|[?&](token|code|key|otp)=)");

    /** What one message offers: distinct codes and distinct qualifying links. */
    public record Extraction(Set<String> codes, Set<String> links) {

        public boolean ambiguous() {
            return codes.size() > 1 || (codes.isEmpty() && links.size() > 1);
        }

        public boolean empty() {
            return codes.isEmpty() && links.isEmpty();
        }

        /** The single code, when there is exactly one. */
        public String code() {
            return codes.size() == 1 ? codes.iterator().next() : null;
        }

        /** The single link, used only when the message has no code. */
        public String link() {
            return codes.isEmpty() && links.size() == 1 ? links.iterator().next() : null;
        }
    }

    private VerificationContentExtractor() {
    }

    public static Extraction extract(String subject, String text, List<String> links, String employerDomain) {
        String scan = TrackText.scanText(subject, text);
        Set<String> codes = new LinkedHashSet<>();
        collect(FORWARD.matcher(scan), codes);
        collect(BACKWARD.matcher(scan), codes);
        Set<String> verificationLinks = new LinkedHashSet<>();
        for (String link : links == null ? List.<String>of() : links) {
            String safe = safeVerificationLink(link, employerDomain);
            if (safe != null) verificationLinks.add(safe);
        }
        return new Extraction(codes, verificationLinks);
    }

    private static void collect(Matcher matcher, Set<String> codes) {
        while (matcher.find() && codes.size() < 10) {
            String digits = matcher.group(1);
            if (!YEAR.matcher(digits).matches()) codes.add(digits);
        }
    }

    /** The link normalised (fragment and trailing punctuation removed) when it qualifies, else null. */
    static String safeVerificationLink(String link, String employerDomain) {
        if (link == null || employerDomain == null) return null;
        String candidate = link.trim();
        while (!candidate.isEmpty() && ".,;:!?)".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.length() > MailgunInboundParser.MAX_LINK_LENGTH) return null;
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (Exception malformed) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) return null;
        if (uri.getPort() != -1 && uri.getPort() != 443) return null;
        String host = uri.getHost();
        if (host == null || !MailAddresses.sameOrSubdomain(host.toLowerCase(Locale.ROOT), employerDomain)) return null;
        String pathAndQuery = (uri.getRawPath() == null ? "" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        if (!VERIFICATION_PATH.matcher(pathAndQuery).find()) return null;
        int fragment = candidate.indexOf('#');
        return fragment < 0 ? candidate : candidate.substring(0, fragment);
    }
}
