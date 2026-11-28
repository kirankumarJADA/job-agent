package com.personal.jobagent.common;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle guard consulted by scheduled background workers before opening a
 * transaction or acquiring a JDBC connection.
 *
 * <p>Prevents scheduled tasks from firing against a closing Spring
 * {@code ApplicationContext} or a closed {@link HikariDataSource} during
 * shutdown and Testcontainers teardown, without disabling recovery or outbox
 * polling in a healthy running application.
 */
@Component
public class SchedulerDatabaseGuard implements SmartLifecycle, ApplicationListener<ContextClosedEvent>, ApplicationContextAware, DisposableBean {

    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile ApplicationContext applicationContext;

    @Autowired(required = false)
    private DataSource dataSource;

    public SchedulerDatabaseGuard() {
    }

    public SchedulerDatabaseGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /** True only when the Spring context is running and its connection pool has not closed. */
    public boolean isReady() {
        if (!active.get()) {
            return false;
        }
        DataSource ds = this.dataSource;
        if (ds instanceof HikariDataSource hikari && (hikari.isClosed() || !hikari.isRunning())) {
            return false;
        }
        return true;
    }

    /** Marks the guard inactive immediately when context or container teardown begins. */
    public void deactivate() {
        active.set(false);
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (event != null && (this.applicationContext == null || event.getApplicationContext() == this.applicationContext)) {
            deactivate();
        }
    }

    @PreDestroy
    public void onPreDestroy() {
        deactivate();
    }

    @Override
    public void destroy() {
        deactivate();
    }

    @Override
    public void start() {
        active.set(true);
    }

    @Override
    public void stop() {
        deactivate();
    }

    @Override
    public void stop(Runnable callback) {
        deactivate();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return active.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
