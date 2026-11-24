package com.personal.jobagent.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;

import java.util.Objects;

/**
 * The single way tests load a Flyway instance they build by hand.
 *
 * <p>V064 builds {@code applications_follow_up_idx} with {@code CREATE INDEX CONCURRENTLY}, which waits for
 * every transaction older than itself to finish. Flyway's PostgreSQL schema-history lock is transactional by
 * default, so Flyway's own lock connection stays "idle in transaction" for the whole run and the concurrent
 * build waits on its virtualxid indefinitely: the migration hangs instead of failing. The application avoids
 * this with {@code spring.flyway.postgresql.transactional-lock=false} in {@code application.yml}, but a
 * hand-built {@code Flyway.configure()} never reads Spring properties. That is how
 * {@code FlywayBootstrapRecoveryStrategyIT} hung under {@code mvn verify} until the 45-minute timeout killed it
 * (Wave 2, C2).
 *
 * <p>{@link #load} applies the same session-level advisory lock the application uses, then loads. Call it as
 * the last step, after every other setting. {@code FlywayHandBuiltLockGuardTest} fails the build if any other
 * class calls {@link FluentConfiguration#load()} or {@code new Flyway(Configuration)} directly, so a future
 * hand-built Flyway cannot silently fall back to the transactional lock.
 */
public final class FlywayTestSupport {

    private FlywayTestSupport() {
    }

    /**
     * Loads {@code configuration} with the application's session-level PostgreSQL lock.
     *
     * @param configuration a fully configured builder; nothing should be set on it after this call
     * @return the loaded Flyway, whose PostgreSQL extension has {@code transactionalLock = false}
     */
    public static Flyway load(FluentConfiguration configuration) {
        PostgreSQLConfigurationExtension postgresql = Objects.requireNonNull(
                configuration.getPluginRegister().getPlugin(PostgreSQLConfigurationExtension.class),
                "flyway-database-postgresql must be on the test classpath");
        postgresql.setTransactionalLock(false);
        return configuration.load();
    }
}
