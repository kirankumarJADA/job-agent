package com.personal.jobagent.common;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback pattern converter that routes every formatted log message through {@link LogScrubber}.
 */
public class ScrubbingMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        if (event == null) {
            return "";
        }
        String scrubbed = LogScrubber.scrub(event.getFormattedMessage());
        return scrubbed == null ? "" : scrubbed;
    }
}
