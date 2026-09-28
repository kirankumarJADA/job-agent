package com.personal.jobagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configuration half of the V024 correction, pinned without starting a
 * Spring context.
 *
 * <p>V024 neutralizes the seeded development credential — whose password is
 * published in V003's comment — unless a deployment says it is local
 * development. That decision is a single property, and it is the kind of thing
 * that gets flipped back by a well-meaning edit ("local development needs the
 * seed, let me just make everywhere keep it"). These tests make such an edit
 * fail here rather than in production, and they pin the one thing that ties the
 * property to the SQL: the placeholder name itself.
 */
class SeededDevCredentialConfigurationTest {

    private static final String PLACEHOLDER_KEY = "spring.flyway.placeholders.remove_seed_dev_account";
    private static final String PLACEHOLDER_NAME = "remove_seed_dev_account";
    private static final String LOCAL_PROFILE_FILE = "application-local.yml";
    private static final String MIGRATION = "db/migration/V024__neutralize_seeded_dev_credential.sql";

    @Test
    void theDefaultIsToRemoveThePublishedCredential() throws IOException {
        assertThat(value("application.yml"))
                .as("an unknown, misspelled or future profile must inherit the secure default")
                .isEqualTo("true");
    }

    @Test
    void localDevelopmentKeepsTheSeededLoginItSignsInWith() throws IOException {
        assertThat(value(LOCAL_PROFILE_FILE))
                .as("local inspection mode signs in with the seeded account, so the local profile keeps it")
                .isEqualTo("false");
    }

    @Test
    void theOnlyConfigurationThatKeepsTheCredentialIsLocalDevelopment() throws IOException {
        Resource[] configurations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:application*.yml");
        assertThat(configurations).as("expected profile configuration files on the classpath").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Resource configuration : configurations) {
            String name = configuration.getFilename();
            if (LOCAL_PROFILE_FILE.equals(name)) {
                continue;
            }
            if ("false".equals(value(name))) {
                offenders.add(name);
            }
        }

        assertThat(offenders)
                .as("only %s may keep the published development credential", LOCAL_PROFILE_FILE)
                .isEmpty();
    }

    @Test
    void theMigrationIsDrivenByExactlyThatPlaceholder() throws IOException {
        String script = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);

        // The name must match the property key, or Flyway fails the migration
        // (loudly) at deploy time instead of quietly skipping the correction.
        assertThat(script).contains("${" + PLACEHOLDER_NAME + "}");
        // A guard that accepts only the two expected values keeps a typo loud.
        assertThat(script).contains("'${" + PLACEHOLDER_NAME + "}' in ('true', 'false')");
        // Neutralize, never delete: profiles and everything under them cascade
        // from users, so a delete could destroy real production data.
        assertThat(script.toLowerCase(java.util.Locale.ROOT)).doesNotContain("delete from users");
    }

    /** The placeholder's value in a configuration file, as a string. */
    private static String value(String resource) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        assertThat(sources).as(resource + " must be on the classpath").isNotEmpty();
        // YAML parses the bare `true`/`false` as booleans; the comparison is on
        // the text so it stays honest about what the file says.
        return String.valueOf(sources.get(0).getProperty(PLACEHOLDER_KEY));
    }
}
