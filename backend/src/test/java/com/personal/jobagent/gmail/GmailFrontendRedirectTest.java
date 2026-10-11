package com.personal.jobagent.gmail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GmailFrontendRedirectTest {

    @Test
    void callbackReturnsToConfiguredFrontendOriginRatherThanBackendOrigin() {
        assertThat(GmailIntegrationController.frontendApplicationsUrl(
                "https://job-agent-beige.vercel.app", "gmail=connected"))
                .isEqualTo("https://job-agent-beige.vercel.app/applications?track&gmail=connected");
    }

    @Test
    void frontendRedirectRejectsAnUntrustedOrMalformedOrigin() {
        assertThatThrownBy(() -> GmailIntegrationController.frontendApplicationsUrl(
                "https://user@evil.example/path", "gmail=connected"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GmailIntegrationController.frontendApplicationsUrl(
                "https://example.com", "https://evil.example"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
