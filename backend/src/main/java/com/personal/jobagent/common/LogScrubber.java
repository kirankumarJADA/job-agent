package com.personal.jobagent.common;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Central scrub rules for anything that reaches a log line or an
 * llm_calls.request_redacted / response_excerpt column. This is the
 * mechanism backing "no passwords/OTPs in logs" (architecture review gap #9)
 * — intent alone isn't a control, this is.
 *
 * Wired into logback-spring.xml as a custom converter, and called directly
 * by any module persisting raw request/response payloads (e.g. the LLM
 * ledger in Phase 1e).
 */
public final class LogScrubber {

    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            // URL userinfo credentials: https://user:pass@host -> https://***REDACTED***@host
            Pattern.compile("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@"),
            // Quoted JSON / config key-value pairs
            Pattern.compile("(?i)(\"?(?:password|passwd|pwd|passcode|otp|pin|api[_-]?key|authorization|token|lease[_-]?token|secret|credential|signature|sig|jwt|sid|cookie|verification[_-]?code|activation[_-]?code|magic|nonce|ticket|verifier)\"?\\s*[:=]\\s*\")((?:\\\\.|[^\"])*)(\")"),
            // Unquoted JSON numeric / boolean / bare values
            Pattern.compile("(?i)(\"(?:password|passwd|pwd|passcode|otp|pin|api[_-]?key|authorization|token|lease[_-]?token|secret|credential|signature|sig|jwt|sid|code|verification[_-]?code|activation[_-]?code|magic|nonce|ticket|verifier)\"\\s*:\\s*)(?:[0-9]{1,12}|true|false)([\\s,}\\]])"),
            // Header lines: Authorization / Cookie / Set-Cookie
            Pattern.compile("(?im)(^(?:authorization|cookie|set-cookie)\\s*[:=]\\s*)([^\\r\\n]+)"),
            Pattern.compile("(?i)\\b(authorization\\s*[:=]\\s*(?:bearer|basic|token)\\s+)[A-Za-z0-9._~+/=-]+"),
            // Bearer / Basic tokens anywhere
            Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{6,}"),
            // Query parameters & unquoted key=value secrets
            Pattern.compile("(?i)((?:^|[?&\\s,;])(?:password|passwd|pwd|passcode|otp|pin|api[_-]?key|authorization|token|lease[_-]?token|secret|credential|signature|sig|jwt|sid|cookie|code|verification[_-]?code|activation[_-]?code|auth[_-]?code|access[_-]?key|magic|nonce|ticket|verifier|key)=)([^&\\s\"'`;),<>\\]}{]+)"),
            // Stage 3I Phase 6: other credential-bearing headers, anywhere on a line
            Pattern.compile("(?i)\\b((?:x-api-key|api-key|x-auth-token|x-access-token|proxy-authorization|x-csrf-token|x-xsrf-token)\\s*[:=]\\s*)([^\\s,;\"']+)"),
            // Stage 3I Phase 6: unquoted "key: value" secrets (YAML, prose, toString output)
            Pattern.compile("(?i)\\b((?:password|passwd|pwd|passcode|otp|pin|token|lease[_-]?token|secret|credential|signature|jwt|verification[_-]?code|activation[_-]?code|one[_-]?time[_-]?code|nonce|ticket|verifier)\\s*:\\s*)(?![\"*])([^\\s,;'}\\]]+)"),
            // Stage 3I Phase 6: a bare numeric "code: 482913" (4+ digits, so statuses such as "code: 500" stay readable)
            Pattern.compile("(?i)\\b((?:security |login |auth |access |verification |confirmation )?code\\s*:\\s*)([0-9]{4,10})\\b"),
            // Stage 3I Phase 6: bare JSON Web Tokens anywhere
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}")
    );

    private static final String[] REPLACEMENTS = {
            "$1***REDACTED***@",
            "$1***REDACTED***$3",
            "$1\"***REDACTED***\"$2",
            "$1***REDACTED***",
            "$1***REDACTED***",
            "$1 ***REDACTED***",
            "$1***REDACTED***",
            "$1***REDACTED***",
            "$1***REDACTED***",
            "$1***REDACTED***",
            "***REDACTED_JWT***"
    };

    private LogScrubber() {
    }

    public static String scrub(String raw) {
        if (raw == null) {
            return null;
        }
        String result = raw;
        for (int i = 0; i < SENSITIVE_PATTERNS.size(); i++) {
            result = SENSITIVE_PATTERNS.get(i).matcher(result).replaceAll(REPLACEMENTS[i]);
        }
        return result;
    }
}
