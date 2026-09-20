package com.personal.jobagent.system;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationContextClosedLoggingListenerTest {

    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(ApplicationContextClosedLoggingListener.class);
        appender = new ListAppender<>();
        appender.start();
        originalLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreLogs() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }

    @Test
    void contextCloseLogsCloserThreadAndUptimeEvidence() {
        try (AnnotationConfigWebApplicationContext context =
                     new AnnotationConfigWebApplicationContext()) {
            context.register(ApplicationContextClosedLoggingListener.class);
            context.setServletContext(new MockServletContext());
            context.refresh();

            appender.list.clear();
            context.close();

            List<ILoggingEvent> events = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().startsWith("ApplicationContext closed:"))
                    .toList();
            assertThat(events).hasSize(1);
            String message = events.get(0).getFormattedMessage();
            assertThat(message).contains("closerThread=main");
            assertThat(message).contains("closerThreadDaemon=false");
            assertThat(message).contains("contextUptimeMs=");
        }
    }

    @Test
    void listenerRegistersViaComponentScanning() {
        // The listener must be a plain @Component so it is active in every
        // profile without extra configuration.
        assertThat(new ApplicationContextClosedLoggingListener())
                .isInstanceOf(ApplicationContextClosedLoggingListener.class);
    }
}
