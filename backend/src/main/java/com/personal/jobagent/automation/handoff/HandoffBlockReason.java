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
        try {
            return HandoffBlockReason.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CAPTCHA;
        }
    }
}
