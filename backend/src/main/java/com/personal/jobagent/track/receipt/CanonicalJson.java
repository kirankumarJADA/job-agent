package com.personal.jobagent.track.receipt;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The worker's {@code canonicalJson} ({@code worker/src/receipt.js}) reproduced
 * exactly, so a digest the worker computed over canonical evidence can be
 * re-derived on the server: object keys sorted by UTF-16 code unit, no
 * whitespace, strings escaped exactly as ECMAScript {@code JSON.stringify}
 * escapes them (a lower-case four-hex-digit escape for other control
 * characters, lone surrogates escaped). Only the value types evidence uses are supported:
 * objects, arrays, strings, booleans, null and integers. A fractional number
 * is refused rather than risk a formatting difference.
 *
 * <p>The cross-language contract is pinned by
 * {@code contracts/submissionEvidenceV2Fixtures.json}, which both the worker
 * and backend test suites check.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String write(JsonNode node) {
        StringBuilder out = new StringBuilder();
        append(out, node);
        return out.toString();
    }

    private static void append(StringBuilder out, JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) keys.add(it.next());
            keys.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(',');
                string(out, keys.get(i));
                out.append(':');
                append(out, node.get(keys.get(i)));
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                append(out, node.get(i));
            }
            out.append(']');
        } else if (node.isTextual()) {
            string(out, node.textValue());
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else if (node.isIntegralNumber()) {
            out.append(node.bigIntegerValue().toString());
        } else if (node.isNumber()) {
            double d = node.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.007199254740992E15) {
                out.append((long) d);
            } else {
                throw new IllegalArgumentException("canonical evidence may not contain fractional numbers");
            }
        } else {
            throw new IllegalArgumentException("unsupported JSON node type: " + node.getNodeType());
        }
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                            out.append(c).append(s.charAt(++i));
                        } else {
                            out.append(String.format("\\u%04x", (int) c));
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
