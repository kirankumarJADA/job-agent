package com.personal.jobagent.track.receipt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads the raw browser evidence a Universal worker recorded, after the
 * server has decrypted it and checked its server-side digest.
 *
 * <p><b>Contract {@value #V2}.</b> The worker's {@code universal_confirmation.js}
 * writes canonical JSON (see {@link CanonicalJson}) whose {@code evidenceSha256}
 * is the SHA-256 of the canonical serialisation of every other field. It adds
 * to the v1 fields: the attempt correlation, the redirect chain (each observed
 * host and status), the final page origin, the source of a confirmation
 * identifier, and per-document upload proof that keeps "attached to the form"
 * separate from "seen in the submission request" — the browser cannot observe
 * file bytes in a multipart request, so no v2 record ever claims an employer
 * received a file.
 *
 * <p><b>Compatibility.</b> Evidence recorded before v2 ({@value #V1}, or with
 * no schema marker) is still readable: its v2-only facts are reported as not
 * recorded, never as failed, so historical evidence is not invalidated by the
 * schema change.
 */
public final class UniversalSubmissionEvidence {

    public static final String V1 = "robin-submission-evidence-v1";
    public static final String V2 = "robin-submission-evidence-v2";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public enum Kind { V2, V1, UNVERSIONED, UNKNOWN_VERSION, UNPARSEABLE }

    public enum DigestStatus { VALID, MISMATCH, ABSENT }

    public record Hop(String host, Integer status) {}

    public record Document(String kind, String artifactSha256, String formAttachment, String submissionRequestFilePart,
                           String uploadVerification) {
        /** True only when the attached artifact's file part was seen in the accepted submission request. */
        public boolean filePartObserved() {
            return "FILE_PART_OBSERVED".equals(uploadVerification);
        }
    }

    public record Parsed(Kind kind, String schemaVersion, DigestStatus digest, UUID planId, UUID applicationId,
                         UUID jobId, List<Hop> redirectChain, String finalOrigin, Map<String, Document> documents,
                         String submittedCvSha256, String submittedCoverLetterSha256, String observedAt,
                         String identifierSource) {

        public boolean isV2() {
            return kind == Kind.V2;
        }

        static Parsed legacy(Kind kind, String schemaVersion) {
            return new Parsed(kind, schemaVersion, DigestStatus.ABSENT, null, null, null, List.of(), null, Map.of(),
                    null, null, null, null);
        }
    }

    private UniversalSubmissionEvidence() {
    }

    public static Parsed parse(String raw, ObjectMapper json) {
        if (raw == null || raw.isBlank()) return Parsed.legacy(Kind.UNPARSEABLE, null);
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (Exception e) {
            return Parsed.legacy(Kind.UNPARSEABLE, null);
        }
        if (root == null || !root.isObject()) return Parsed.legacy(Kind.UNPARSEABLE, null);
        String schema = text(root, "schemaVersion");
        if (!V2.equals(schema)) {
            if (V1.equals(schema)) {
                return Parsed.legacy(Kind.V1, schema);
            }
            if (root.has("schemaVersion")) {
                // Explicit unknown, blank or non-string schemaVersion fails closed rather than posing as legacy.
                return Parsed.legacy(Kind.UNKNOWN_VERSION, schema);
            }
            return Parsed.legacy(Kind.UNVERSIONED, null);
        }

        DigestStatus digest = DigestStatus.ABSENT;
        String claimed = text(root, "evidenceSha256");
        if (claimed != null) {
            ObjectNode body = ((ObjectNode) root).deepCopy();
            body.remove("evidenceSha256");
            String computed;
            try {
                computed = ReceiptVerificationPolicy.computeEvidenceSha256(CanonicalJson.write(body));
            } catch (IllegalArgumentException e) {
                computed = null;
            }
            digest = computed != null && computed.equalsIgnoreCase(claimed) ? DigestStatus.VALID : DigestStatus.MISMATCH;
        }

        JsonNode corr = root.path("attemptCorrelation");
        List<Hop> hops = new ArrayList<>();
        for (JsonNode hop : root.path("redirectChain")) {
            String host = text(hop, "host");
            Integer status = hop.path("status").isIntegralNumber() ? hop.path("status").intValue() : null;
            hops.add(new Hop(host, status));
        }
        Map<String, Document> documents = new LinkedHashMap<>();
        JsonNode docs = root.path("documents");
        for (String kind : List.of("cv", "coverLetter")) {
            JsonNode d = docs.path(kind);
            if (!d.isObject()) continue;
            documents.put(kind, new Document(kind, sha(text(d, "artifactSha256")), text(d, "formAttachment"),
                    text(d, "submissionRequestFilePart"), text(d, "uploadVerification")));
        }
        return new Parsed(Kind.V2, schema, digest, uuid(text(corr, "planId")), uuid(text(corr, "applicationId")),
                uuid(text(corr, "jobId")), List.copyOf(hops), text(root, "finalOrigin"), Map.copyOf(documents),
                sha(text(root, "submittedCvSha256")), sha(text(root, "submittedCoverLetterSha256")),
                text(root, "observedAt"), text(root, "confirmationIdentifierSource"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isTextual() && !v.textValue().isBlank() ? v.textValue() : null;
    }

    private static String sha(String value) {
        return value != null && SHA256.matcher(value).matches() ? value : null;
    }

    private static UUID uuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
