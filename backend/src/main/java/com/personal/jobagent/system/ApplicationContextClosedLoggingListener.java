package com.personal.jobagent.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * Logs when the Spring ApplicationContext is deliberately closed while the
 * JVM is still alive, recording which thread is doing the closing.
 *
 * <p>Context: the Render deployment once showed a clean graceful shutdown
 * immediately after the service was reported live, and the repository at the
 * time contained no System.exit/halt calls, no devtools, and no explicit
 * context close. An external process (container runtime, platform health
 * manager, or launcher) sends SIGTERM in that scenario and the JVM's normal
 * shutdown sequence closes the context — indistinguishable in application
 * logs from an in-process close. If such an in-process close ever happens,
 * this listener makes it self-explaining; if the log shows only an external
 * SIGTERM, the platform is the origin. It records evidence; it changes no
 * shutdown behavior.
 */
@Component
public class ApplicationContextClosedLoggingListener {
    private static final Logger log =
            LoggerFactory.getLogger(ApplicationContextClosedLoggingListener.class);

    @EventListener(ContextClosedEvent.class)
    void onContextClosed(ContextClosedEvent event) {
        Thread closer = Thread.currentThread();
        log.warn("ApplicationContext closed: closerThread={}, closerThreadDaemon={}, "
                        + "jvmUptimeMs={}, contextUptimeMs={}",
                closer.getName(), closer.isDaemon(), jvmUptimeMs(),
                contextUptimeMs(event.getApplicationContext()));
    }

    private static long jvmUptimeMs() {
        try {
            return ManagementFactory.getRuntimeMXBean().getUptime();
        } catch (Exception e) {
            return -1;
        }
    }

    private static long contextUptimeMs(ApplicationContext context) {
        try {
            return context.getStartupDate() <= 0
                    ? -1
                    : System.currentTimeMillis() - context.getStartupDate();
        } catch (Exception e) {
            return -1;
        }
    }
}
