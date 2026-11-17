package com.personal.jobagent.profile;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDate;

/**
 * Deserializes date inputs in ISO-8601 full date (YYYY-MM-DD), month-level (YYYY-MM),
 * or year-only (YYYY) formats without throwing parsing errors.
 */
public class FlexibleLocalDateDeserializer extends JsonDeserializer<LocalDate> {

    @Override
    public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.strip();
        if (s.matches("\\d{4}")) {
            return LocalDate.of(Integer.parseInt(s), 1, 1);
        }
        if (s.matches("\\d{4}-\\d{2}")) {
            return LocalDate.parse(s + "-01");
        }
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(s);
        }
        return LocalDate.parse(s);
    }
}
