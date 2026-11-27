package com.personal.jobagent.config;

import com.personal.jobagent.common.SchedulerDatabaseGuard;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.util.ReflectionUtils;
import org.testcontainers.containers.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ensures per-class Testcontainers Spring {@code ApplicationContext} instances are
 * torn down immediately at the end of that test class instead of staying open in
 * Spring's context cache after JUnit stops the underlying Docker container.
 *
 * <p>Tests within the same class continue to share both the container and the context;
 * non-Testcontainers contexts remain cached across test classes.
 */
public class TestcontainersContextCleanupListener extends AbstractTestExecutionListener {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        Class<?> testClass = testContext.getTestClass();
        if (!usesPerClassContainer(testClass) || !testContext.hasApplicationContext()) {
            return;
        }
        try {
            ApplicationContext ctx = testContext.getApplicationContext();
            ctx.getBeanProvider(SchedulerDatabaseGuard.class).ifAvailable(SchedulerDatabaseGuard::deactivate);
            ctx.getBeanProvider(ThreadPoolTaskScheduler.class).ifAvailable(scheduler -> {
                try {
                    scheduler.getScheduledThreadPoolExecutor().shutdownNow();
                } catch (IllegalStateException ignored) {
                    // Scheduler not yet initialized or already terminated.
                }
            });
        } catch (IllegalStateException ignored) {
            // Context already closed.
        }
        testContext.markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
    }

    static boolean usesPerClassContainer(Class<?> testClass) {
        if (testClass == null) {
            return false;
        }
        if (AnnotatedElementUtils.hasAnnotation(testClass, Testcontainers.class)) {
            return true;
        }
        AtomicBoolean found = new AtomicBoolean(false);
        ReflectionUtils.doWithFields(testClass, field -> {
            if (Container.class.isAssignableFrom(field.getType())) {
                found.set(true);
            }
        });
        return found.get();
    }
}
