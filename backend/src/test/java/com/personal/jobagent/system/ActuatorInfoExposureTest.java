package com.personal.jobagent.system;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.BuildInfoContributor;
import org.springframework.boot.actuate.info.GitInfoContributor;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.actuate.info.InfoPropertiesInfoContributor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production hardening pass: {@code GET /actuator/info} used to return {@code {}},
 * so a deployed build could not be identified from the running service.
 *
 * <p>These tests pin two things at once: that build/git metadata IS produced and
 * surfaces through the real {@link InfoEndpoint}, and that it stays narrow.
 * {@code management.info.env.enabled=false} must not be flipped (environment
 * properties can carry datasource URLs and credentials), and the git plugin's
 * allowlist must keep committer identity, author email and remote URLs out of
 * the artifact.
 */
class ActuatorInfoExposureTest {

    /**
     * Exactly the keys the git plugin is configured to write — the four the
     * "simple" git contributor reads. Boot's default (full) output also writes
     * committer/author names, emails, the remote URL and build host.
     */
    private static final Set<String> GIT_KEY_ALLOWLIST = Set.of(
            "git.branch",
            "git.commit.id",
            "git.commit.id.abbrev",
            "git.commit.time");

    @Test
    void buildMetadataIsBakedIntoTheArtifact() throws IOException {
        Properties build = classpath("META-INF/build-info.properties");

        assertThat(build)
                .as("META-INF/build-info.properties must exist so /actuator/info can report the running version")
                .isNotNull();
        // spring-boot-maven-plugin writes these with a "build." key prefix.
        assertThat(build.getProperty("build.name")).isEqualTo("job-agent-backend");
        assertThat(build.getProperty("build.group")).isEqualTo("com.personal");
        assertThat(build.getProperty("build.artifact")).isEqualTo("jobagent");
        assertThat(build.getProperty("build.version")).isNotBlank();
        assertThat(build.getProperty("build.time")).isNotBlank();
        assertThat(build.stringPropertyNames()).allSatisfy(key ->
                assertThat(key.toLowerCase(Locale.ROOT))
                        .as("build metadata must never carry credentials")
                        .doesNotContain("password", "secret", "token", "credential", "apikey", "api-key",
                                "datasource", "redis", "url"));
    }

    @Test
    void gitMetadataStaysInsideTheAllowlist() throws IOException {
        Properties git = classpath("git.properties");
        if (git == null) {
            // Legitimate: the Docker build stage copies only pom.xml + src, so
            // it has no .git and the plugin writes nothing (see DEPLOYMENT.md).
            return;
        }

        assertThat(git.stringPropertyNames()).isSubsetOf(GIT_KEY_ALLOWLIST);
        assertThat(git.stringPropertyNames()).isNotEmpty();
        assertThat(git.stringPropertyNames().toString().toLowerCase(Locale.ROOT))
                .as("no committer/author identity or remote URL may reach the deployed artifact")
                .doesNotContain("user", "email", "url", "host", "message", "author", "committer");
    }

    @Test
    void infoEndpointReportsBuildAndGitOnly() throws IOException {
        Properties build = classpath("META-INF/build-info.properties");
        assertThat(build).isNotNull();

        List<InfoContributor> contributors = new ArrayList<>();
        contributors.add(new BuildInfoContributor(new BuildProperties(build)));
        Properties git = classpath("git.properties");
        if (git != null) {
            // Boot's ProjectInfoAutoConfiguration loads git.properties with a
            // "git." prefix and hands GitProperties the STRIPPED keys, so the
            // test must mirror that loader or the contributor silently yields
            // nothing.
            contributors.add(new GitInfoContributor(new GitProperties(stripPrefix(git, "git.")),
                    InfoPropertiesInfoContributor.Mode.SIMPLE));
        }

        Map<String, Object> info = new InfoEndpoint(contributors).info();

        assertThat(info).containsKey("build");
        assertThat(info.keySet())
                .as("env/java/os/process contributors stay off: they can expose configuration")
                .isSubsetOf(Set.of("build", "git"));

        // Assert on the values that surface, not on Boot's internal nesting:
        // the running build's version and (when available) the deployed commit
        // id must be readable from the endpoint.
        Map<String, String> flat = new LinkedHashMap<>();
        info.forEach((key, value) -> flatten(key, value, flat));
        assertThat(flat.values())
                .as("the running build's version must be visible in /actuator/info")
                .contains(build.getProperty("build.version"));
        if (git != null) {
            assertThat(flat.values())
                    .as("the deployed commit id must be visible in /actuator/info")
                    .contains(git.getProperty("git.commit.id.abbrev"));
        }

        String flatString = flat.toString().toLowerCase(Locale.ROOT);
        assertThat(flatString).doesNotContain("password", "secret", "token", "credential", "datasource",
                "jdbc", "api-key", "apikey", "redis://", "postgres://");
    }

    private static Properties stripPrefix(Properties source, String prefix) {
        Properties stripped = new Properties();
        for (String name : source.stringPropertyNames()) {
            if (name.startsWith(prefix)) {
                stripped.put(name.substring(prefix.length()), source.getProperty(name));
            }
        }
        return stripped;
    }

    private static void flatten(String prefix, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> nested) {
            nested.forEach((key, child) -> flatten(prefix + "." + key, child, out));
        } else {
            out.put(prefix, String.valueOf(value));
        }
    }

    @Test
    void actuatorInfoConfigKeepsEnvironmentDisabledAndGitSimple() throws IOException {
        Object envEnabled = yaml("application.yml", "management.info.env.enabled");
        Object gitMode = yaml("application.yml", "management.info.git.mode");
        Object exposure = yaml("application.yml", "management.endpoints.web.exposure.include");

        assertThat(String.valueOf(envEnabled))
                .as("environment properties must stay out of /actuator/info")
                .isEqualTo("false");
        assertThat(String.valueOf(gitMode))
                .as("simple git mode exposes branch/commit only; full mode would add remote URLs and identity")
                .isEqualTo("simple");
        assertThat(String.valueOf(exposure)).contains("info").contains("health");
    }

    private static Object yaml(String resource, String key) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        assertThat(sources).as(resource + " must be on the classpath").isNotEmpty();
        return sources.get(0).getProperty(key);
    }

    private static Properties classpath(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = resource.getInputStream()) {
            properties.load(in);
        }
        return properties;
    }
}
