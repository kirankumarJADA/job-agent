package com.personal.jobagent.automation.handoff;

/**
 * Phase 3: Reasons an automated browser session was halted at an employer security boundary.
 */
public enum HandoffBlockReason {
    CAPTCHA,
    MFA,
    CLOUDFLARE_TURNSTILE,
    EMPLOYER_SECURITY_CHECK;

    public static HandoffBlockReason parse(String value) {
        if (value == null || value.isBlank()) {
            return CAPTCHA;
        }
        String normalized = value.trim().toUpperCase();
        if (normalized.contains("MFA")) {
            return MFA;
        }
        if (normalized.contains("TURNSTILE") || normalized.contains("CLOUDFLARE")) {
            return CLOUDFLARE_TURNSTILE;
        }
        if (normalized.contains("SECURITY") || normalized.contains("CHALLENGE")) {
            return EMPLOYER_SECURITY_CHECK;
        }
        if (normalized.contains("CAPTCHA")) {
            return CAPTCHA;
        }
        try {
            return HandoffBlockReason.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return CAPTCHA;
        }
    }
}
