package com.personal.jobagent.config;

import com.personal.jobagent.common.SchedulerDatabaseGuard;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.util.ReflectionUtils;
import org.testcontainers.containers.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ensures per-class Testcontainers Spring {@code ApplicationContext} instances are
 * torn down cleanly at the end of that test class instead of staying open in
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
            var guardProvider = ctx.getBeanProvider(SchedulerDatabaseGuard.class);
            if (guardProvider != null) {
                guardProvider.stream().forEach(SchedulerDatabaseGuard::deactivate);
            }
            var schedulerProvider = ctx.getBeanProvider(ThreadPoolTaskScheduler.class);
            if (schedulerProvider != null) {
                schedulerProvider.stream().forEach(scheduler -> {
                    try {
                        ExecutorService exec = scheduler.getScheduledThreadPoolExecutor();
                        shutdownAndAwait(exec);
                    } catch (IllegalStateException ignored) {
                        // Scheduler not yet initialized or already terminated.
                    }
                });
            }
            var executorProvider = ctx.getBeanProvider(ThreadPoolTaskExecutor.class);
            if (executorProvider != null) {
                executorProvider.stream().forEach(executor -> {
                    try {
                        shutdownAndAwait(executor.getThreadPoolExecutor());
                    } catch (IllegalStateException ignored) {
                    }
                });
            }
            shutdownNamedDisposable(ctx, "searchPlansController");
            shutdownNamedDisposable(ctx, "searchPlanExecutionCoordinator");
        } catch (IllegalStateException ignored) {
            // Context already closed.
        }
        testContext.markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
    }

    private static void shutdownNamedDisposable(ApplicationContext ctx, String beanName) {
        if (!ctx.containsBean(beanName)) {
            return;
        }
        try {
            Object bean = ctx.getBean(beanName);
            if (bean instanceof DisposableBean disposable) {
                disposable.destroy();
            }
        } catch (Exception ignored) {
        }
    }

    private static void shutdownAndAwait(ExecutorService exec) {
        if (exec == null) return;
        exec.shutdown();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                exec.shutdownNow();
                exec.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
