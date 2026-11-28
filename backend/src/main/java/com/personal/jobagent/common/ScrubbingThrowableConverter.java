package com.personal.jobagent.common;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.IThrowableProxy;

/**
 * Logback throwable converter that scrubs secret patterns from exception messages and stack traces.
 */
public class ScrubbingThrowableConverter extends ThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        String rendered = super.throwableProxyToString(tp);
        return rendered == null ? "" : LogScrubber.scrub(rendered);
    }
}
