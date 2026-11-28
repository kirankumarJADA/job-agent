package com.personal.jobagent.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import net.logstash.logback.decorate.JsonGeneratorDecorator;

import java.io.IOException;

/**
 * Scrubs sensitive fields from all JSON log strings emitted through LogstashEncoder.
 */
public class ScrubbingJsonGeneratorDecorator implements JsonGeneratorDecorator {

    @Override
    public JsonGenerator decorate(JsonGenerator generator) {
        return new JsonGeneratorDelegate(generator, true) {
            @Override
            public void writeString(String text) throws IOException {
                super.writeString(text == null ? null : LogScrubber.scrub(text));
            }

            @Override
            public void writeString(char[] buffer, int offset, int length) throws IOException {
                String raw = new String(buffer, offset, length);
                super.writeString(LogScrubber.scrub(raw));
            }
        };
    }
}
