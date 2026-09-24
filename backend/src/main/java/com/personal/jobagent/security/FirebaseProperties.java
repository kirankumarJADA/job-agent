package com.personal.jobagent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Firebase Admin credentials, sourced exclusively from the environment:
 *
 * <pre>
 *   FIREBASE_PROJECT_ID
 *   FIREBASE_CLIENT_EMAIL
 *   FIREBASE_PRIVATE_KEY
 * </pre>
 *
 * <p>No default values are supplied for any of them. A missing value stays
 * blank/absent here rather than silently falling back to something plausible,
 * so {@link #missingKeys()} can name exactly what an operator has not set yet
 * and the sign-in path can report that plainly instead of failing somewhere
 * deeper with an opaque SDK exception.
 *
 * <p>{@code privateKey} is a service-account PEM. It travels through
 * environment variables, where the newlines are conventionally escaped as
 * {@code \n} — {@link #normalisedPrivateKey()} restores them. Both real
 * newlines and literal {@code \n} sequences are accepted.
 *
 * <p>The value is never logged, never returned in an API response, and never
 * written to the repository.
 */
@Component
@ConfigurationProperties(prefix = "app.firebase")
public class FirebaseProperties {

    /** Firebase/GCP project id, e.g. {@code robin-job-agent}. */
    private String projectId;

    /** Service-account client email, e.g. {@code firebase-adminsdk-xxxx@<project>.iam.gserviceaccount.com}. */
    private String clientEmail;

    /** Service-account private key (PEM, PKCS#8). */
    private String privateKey;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getClientEmail() {
        return clientEmail;
    }

    public void setClientEmail(String clientEmail) {
        this.clientEmail = clientEmail;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    /**
     * True only when every credential the Admin SDK needs is present. A
     * partially configured deployment is treated as NOT configured: a verifier
     * that starts up with, say, a client email but no key would fail on the
     * first real sign-in attempt with a much less actionable error.
     */
    public boolean isConfigured() {
        return missingKeys().isEmpty();
    }

    /**
     * Names of the environment variables that still need to be set, in the
     * order an operator would read them. Empty when {@link #isConfigured()}.
     */
    public List<String> missingKeys() {
        List<String> missing = new ArrayList<>();
        if (isBlank(projectId)) {
            missing.add("FIREBASE_PROJECT_ID");
        }
        if (isBlank(clientEmail)) {
            missing.add("FIREBASE_CLIENT_EMAIL");
        }
        if (isBlank(privateKey)) {
            missing.add("FIREBASE_PRIVATE_KEY");
        }
        return List.copyOf(missing);
    }

    /**
     * The private key with escaped newlines restored. Returns {@code null}
     * when no key is configured, so callers cannot accidentally treat an
     * empty string as a usable credential.
     */
    public String normalisedPrivateKey() {
        if (isBlank(privateKey)) {
            return null;
        }
        return privateKey.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\r\n", "\n").trim();
    }

    /** Never renders credential material. */
    @Override
    public String toString() {
        return "FirebaseProperties{configured=" + isConfigured()
                + ", projectId=" + (isBlank(projectId) ? "<unset>" : projectId)
                + ", clientEmail=" + (isBlank(clientEmail) ? "<unset>" : "<set>")
                + ", privateKey=" + (isBlank(privateKey) ? "<unset>" : "<set>")
                + "}";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
