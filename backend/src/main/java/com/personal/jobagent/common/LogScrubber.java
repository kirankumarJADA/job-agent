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
            Pattern.compile("(?i)(\"?password\"?\\s*[:=]\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"?otp\"?\\s*[:=]\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"?api[_-]?key\"?\\s*[:=]\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"?authorization\"?\\s*[:=]\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"?token\"?\\s*[:=]\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"?secret\"?\\s*[:=]\\s*\")[^\"]*(\")")
    );

    private static final String REDACTED = "$1***REDACTED***$2";

    private LogScrubber() {
    }

    public static String scrub(String raw) {
        if (raw == null) {
            return null;
        }
        String result = raw;
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            result = pattern.matcher(result).replaceAll(REDACTED);
        }
        return result;
    }
}
