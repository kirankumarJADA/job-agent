package com.personal.jobagent.architecture;

import com.personal.jobagent.config.FlywayTestSupport;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 2 / C2 regression guard: no hand-built Flyway may fall back to the transactional schema-history lock.
 *
 * <p>V064 runs {@code CREATE INDEX CONCURRENTLY}. Under Flyway's default transactional PostgreSQL lock, Flyway's
 * own lock connection is "idle in transaction" for the whole run and the concurrent build waits on it forever.
 * The application is protected by {@code spring.flyway.postgresql.transactional-lock=false}; a hand-built
 * {@code Flyway.configure()} is not, which is how {@code FlywayBootstrapRecoveryStrategyIT} hung until
 * {@code mvn verify} was killed by its timeout. Every hand-built Flyway must therefore load through
 * {@link FlywayTestSupport#load}.
 */
class FlywayHandBuiltLockGuardTest {

    // Unlike ArchModuleBoundaryTest this deliberately imports TEST classes as well as production classes:
    // every hand-built Flyway in the repository lives in an integration test.
    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.personal.jobagent");

    @Test
    void noClassLoadsAHandBuiltFlywayExceptThroughFlywayTestSupport() {
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName(FlywayTestSupport.class.getName())
                .should().callMethod(FluentConfiguration.class, "load")
                .orShould().callConstructor(Flyway.class, Configuration.class)
                .because("a hand-built Flyway ignores spring.flyway.postgresql.transactional-lock and uses the "
                        + "transactional schema-history lock, on which V064's CREATE INDEX CONCURRENTLY waits "
                        + "forever; tests must load through FlywayTestSupport.load(...), and production code "
                        + "must use the Spring-managed Flyway bean");
        rule.check(CLASSES);
    }

    @Test
    void theGuardCannotPassVacuously() {
        // The import must really contain the integration tests that build Flyway by hand...
        assertThat(CLASSES.contain("com.personal.jobagent.config.FlywayBootstrapRecoveryStrategyIT")).isTrue();
        assertThat(CLASSES.contain("com.personal.jobagent.gmail.GmailConnectionStoreIT")).isTrue();
        // ...and the rule must target the method the helper actually calls, so a renamed Flyway API
        // cannot quietly turn the rule above into a no-op.
        assertThat(CLASSES.get(FlywayTestSupport.class).getMethodCallsFromSelf())
                .anySatisfy(call -> {
                    assertThat(call.getTargetOwner().isEquivalentTo(FluentConfiguration.class)).isTrue();
                    assertThat(call.getName()).isEqualTo("load");
                });
    }

    @Test
    void aHandBuiltFlywayDefaultsToTheTransactionalLock() {
        // Why the helper exists: left alone, Flyway uses the lock V064's concurrent build deadlocks on.
        PostgreSQLConfigurationExtension postgresql = Flyway.configure().getPluginRegister()
                .getPlugin(PostgreSQLConfigurationExtension.class);
        assertThat(postgresql).as("flyway-database-postgresql on the test classpath").isNotNull();
        assertThat(postgresql.isTransactionalLock()).isTrue();
    }

    @Test
    void flywayTestSupportLoadsWithTheApplicationsSessionLevelLock() {
        // Asserted on the LOADED instance rather than the builder: the setting must survive load().
        Flyway flyway = FlywayTestSupport.load(Flyway.configure()
                .dataSource("jdbc:postgresql://localhost:1/never-connected", "unused", "unused"));
        assertThat(flyway.getConfigurationExtension(PostgreSQLConfigurationExtension.class)
                .isTransactionalLock()).isFalse();
    }
}
