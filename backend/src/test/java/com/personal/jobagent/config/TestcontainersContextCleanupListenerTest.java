package com.personal.jobagent.config;

import com.personal.jobagent.common.SchedulerDatabaseGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestcontainersContextCleanupListenerTest {

    @Testcontainers
    static class AnnotatedContainerTest {}

    static class FieldContainerTest {
        static PostgreSQLContainer<?> postgres = null;
    }

    static class PlainUnitTest {}

    @Test
    void detectsTestcontainersClassesAndCleansUpContextOnAfterTestClass() {
        assertThat(TestcontainersContextCleanupListener.usesPerClassContainer(AnnotatedContainerTest.class)).isTrue();
        assertThat(TestcontainersContextCleanupListener.usesPerClassContainer(FieldContainerTest.class)).isTrue();
        assertThat(TestcontainersContextCleanupListener.usesPerClassContainer(PlainUnitTest.class)).isFalse();

        TestcontainersContextCleanupListener listener = new TestcontainersContextCleanupListener();

        TestContext plainCtx = mock(TestContext.class);
        doReturn(PlainUnitTest.class).when(plainCtx).getTestClass();
        listener.afterTestClass(plainCtx);
        verify(plainCtx, never()).markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);

        TestContext containerCtx = mock(TestContext.class);
        ApplicationContext appCtx = mock(ApplicationContext.class);
        SchedulerDatabaseGuard guard = new SchedulerDatabaseGuard();
        @SuppressWarnings("unchecked")
        ObjectProvider<SchedulerDatabaseGuard> guardProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ThreadPoolTaskScheduler> schedulerProvider = mock(ObjectProvider.class);
        when(appCtx.getBeanProvider(SchedulerDatabaseGuard.class)).thenReturn(guardProvider);
        when(appCtx.getBeanProvider(ThreadPoolTaskScheduler.class)).thenReturn(schedulerProvider);
        doReturn(AnnotatedContainerTest.class).when(containerCtx).getTestClass();
        when(containerCtx.hasApplicationContext()).thenReturn(true);
        when(containerCtx.getApplicationContext()).thenReturn(appCtx);

        listener.afterTestClass(containerCtx);
        verify(containerCtx).markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
    }
}
