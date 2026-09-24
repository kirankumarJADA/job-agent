package com.personal.jobagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deployment with no Firebase credentials must say so plainly rather than
 * failing somewhere deep in the SDK. These tests pin that behaviour, and pin
 * that the credentials themselves never appear in a rendered property object.
 */
class FirebasePropertiesTest {

    @Test
    void anEntirelyUnconfiguredServerReportsEveryMissingVariable() {
        FirebaseProperties properties = new FirebaseProperties();

        assertThat(properties.isConfigured()).isFalse();
        assertThat(properties.missingKeys())
                .containsExactly("FIREBASE_PROJECT_ID", "FIREBASE_CLIENT_EMAIL", "FIREBASE_PRIVATE_KEY");
    }

    @Test
    void aPartiallyConfiguredServerIsNotTreatedAsConfigured() {
        // Half a credential is not a credential: starting the SDK with an email
        // but no key would fail on the first sign-in with a far less actionable
        // error than "you have not set FIREBASE_PRIVATE_KEY".
        FirebaseProperties properties = configured("project", "sa@project.iam.gserviceaccount.com", null);

        assertThat(properties.isConfigured()).isFalse();
        assertThat(properties.missingKeys()).containsExactly("FIREBASE_PRIVATE_KEY");
    }

    @Test
    void blankValuesCountAsMissingRatherThanAsPresent() {
        FirebaseProperties properties = configured("project", "   ", "");

        assertThat(properties.isConfigured()).isFalse();
        assertThat(properties.missingKeys())
                .containsExactly("FIREBASE_CLIENT_EMAIL", "FIREBASE_PRIVATE_KEY");
    }

    @Test
    void fullyConfiguredServerNeedsNothingElse() {
        FirebaseProperties properties = configured("project", "sa@project.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----");

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.missingKeys()).isEmpty();
    }

    @Test
    void escapedNewlinesInAnEnvironmentSuppliedKeyAreRestored() {
        // The conventional way to carry a PEM through an environment variable.
        FirebaseProperties properties = configured("project", "sa@project", "-----BEGIN PRIVATE KEY-----\\nMIIabc\\n-----END PRIVATE KEY-----");

        assertThat(properties.normalisedPrivateKey())
                .isEqualTo("-----BEGIN PRIVATE KEY-----\nMIIabc\n-----END PRIVATE KEY-----")
                .doesNotContain("\\n");
    }

    @Test
    void realNewlinesInAKeyAreAlsoAccepted() {
        FirebaseProperties properties = configured("project", "sa@project", "KEY\r\nLINE\r\n");

        assertThat(properties.normalisedPrivateKey()).isEqualTo("KEY\nLINE");
    }

    @Test
    void anAbsentKeyNormalisesToNullNotToAnEmptyString() {
        FirebaseProperties properties = new FirebaseProperties();

        assertThat(properties.normalisedPrivateKey()).isNull();
    }

    @Test
    void toStringNeverRendersCredentialMaterial() {
        String secret = "-----BEGIN PRIVATE KEY-----\nSUPERSECRET\n-----END PRIVATE KEY-----";
        FirebaseProperties properties = configured("robin-project", "sa@project.iam.gserviceaccount.com", secret);

        String rendered = properties.toString();

        assertThat(rendered)
                .doesNotContain("SUPERSECRET")
                .doesNotContain(secret)
                .doesNotContain("sa@project.iam.gserviceaccount.com");
        assertThat(rendered).contains("robin-project").contains("configured=true");
    }

    @Test
    void neitherProfileShipsACommittedFirebaseCredential() throws IOException {
        // Credentials must come from the environment only. If someone ever adds
        // a real default here, this fails.
        for (String profile : List.of("application.yml", "application-prod.yml")) {
            assertThat(String.valueOf(yaml(profile, "app.firebase.project-id")))
                    .as(profile + " project id must be environment-supplied")
                    .isEqualTo("${FIREBASE_PROJECT_ID:}");
            assertThat(String.valueOf(yaml(profile, "app.firebase.client-email")))
                    .as(profile + " client email must be environment-supplied")
                    .isEqualTo("${FIREBASE_CLIENT_EMAIL:}");
            assertThat(String.valueOf(yaml(profile, "app.firebase.private-key")))
                    .as(profile + " private key must be environment-supplied")
                    .isEqualTo("${FIREBASE_PRIVATE_KEY:}");
        }
    }

    @Test
    void productionRequiresAnInviteCodeWhileDevelopmentDefaultsToNotRequiringOne() throws IOException {
        assertThat(String.valueOf(yaml("application-prod.yml", "app.auth.require-invite-code")))
                .as("production must gate new registrations by default")
                .isEqualTo("${APP_REQUIRE_INVITE_CODE:true}");
        assertThat(String.valueOf(yaml("application.yml", "app.auth.require-invite-code")))
                .as("local development defaults to not requiring a code")
                .isEqualTo("${APP_REQUIRE_INVITE_CODE:false}");
    }

    private static Object yaml(String resource, String key) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        assertThat(sources).as(resource + " must be on the classpath").isNotEmpty();
        return sources.get(0).getProperty(key);
    }

    private static FirebaseProperties configured(String projectId, String clientEmail, String privateKey) {
        FirebaseProperties properties = new FirebaseProperties();
        properties.setProjectId(projectId);
        properties.setClientEmail(clientEmail);
        properties.setPrivateKey(privateKey);
        return properties;
    }
}
