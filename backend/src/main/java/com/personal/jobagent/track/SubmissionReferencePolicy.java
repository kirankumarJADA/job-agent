package com.personal.jobagent.track;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compares the confirmation reference the browser observed at submission
 * ({@code submission_evidence.employer_confirmation_id}) with the reference an
 * employer acknowledgement quotes ({@code application_receipts.external_reference})
 * ({@value #VERSION}). Pure and deterministic.
 *
 * <p><b>Conservative normalisation.</b> Only differences that cannot change
 * meaning are removed: surrounding whitespace, a leading {@code #}, letter case.
 * Separators are kept ({@code APP-123} and {@code APP123} differ), because
 * folding them could make two distinct references look identical. A false
 * mismatch sends the candidate to review; a false match would hide a conflict.
 *
 * <p><b>Only confirmation identifiers are compared.</b> A value that is
 * malformed, or that equals one of Robin's own identifiers for this
 * application (application, job or plan id), the job's posting identifier or
 * requisition number, is not an employer confirmation reference and is never
 * compared ({@link Consistency#NOT_COMPARABLE}). Neither original value is
 * ever replaced: the comparison only reports.
 */
public final class SubmissionReferencePolicy {

    public static final String VERSION = "track-reference-policy-v1";

    private static final Pattern WELL_FORMED = Pattern.compile("[A-Z0-9][A-Z0-9._/-]{2,63}");

    public enum Consistency { ALIGNED, CONFLICTING, NOT_COMPARABLE }

    public record Comparison(Consistency consistency, String code, String receiptNormalized,
                             String submissionNormalized) {
        public boolean conflicting() {
            return consistency == Consistency.CONFLICTING;
        }
    }

    /** The conservatively normalised reference, or empty when it is missing or malformed. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.strip();
        if (s.startsWith("#")) s = s.substring(1).strip();
        s = s.toUpperCase(Locale.ROOT);
        return WELL_FORMED.matcher(s).matches() ? Optional.of(s) : Optional.empty();
    }

    /** Robin's own identifiers and the job's posting/requisition tokens, normalised, for exclusion. */
    public static Set<String> disqualifiedIdentifiers(Collection<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values == null) return out;
        for (String v : values) {
            if (v == null || v.isBlank()) continue;
            String token = v.contains(":") ? v.substring(v.lastIndexOf(':') + 1) : v;
            normalize(token).ifPresent(out::add);
            normalize(v).ifPresent(out::add);
        }
        return out;
    }

    public Comparison compare(String receiptReference, String submissionReference, Set<String> disqualified) {
        Set<String> excluded = disqualified == null ? Set.of() : disqualified;
        if (receiptReference == null || receiptReference.isBlank()) {
            return new Comparison(Consistency.NOT_COMPARABLE, "RECEIPT_REFERENCE_MISSING", null, null);
        }
        if (submissionReference == null || submissionReference.isBlank()) {
            return new Comparison(Consistency.NOT_COMPARABLE, "SUBMISSION_REFERENCE_MISSING",
                    normalize(receiptReference).orElse(null), null);
        }
        Optional<String> r = normalize(receiptReference);
        Optional<String> s = normalize(submissionReference);
        if (r.isEmpty()) {
            return new Comparison(Consistency.NOT_COMPARABLE, "RECEIPT_REFERENCE_MALFORMED", null, s.orElse(null));
        }
        if (s.isEmpty()) {
            return new Comparison(Consistency.NOT_COMPARABLE, "SUBMISSION_REFERENCE_MALFORMED", r.get(), null);
        }
        if (excluded.contains(r.get())) {
            return new Comparison(Consistency.NOT_COMPARABLE, "RECEIPT_REFERENCE_NOT_A_CONFIRMATION_ID", r.get(), s.get());
        }
        if (excluded.contains(s.get())) {
            return new Comparison(Consistency.NOT_COMPARABLE, "SUBMISSION_REFERENCE_NOT_A_CONFIRMATION_ID", r.get(), s.get());
        }
        return r.get().equals(s.get())
                ? new Comparison(Consistency.ALIGNED, "REFERENCES_MATCH", r.get(), s.get())
                : new Comparison(Consistency.CONFLICTING, "REFERENCES_DIFFER", r.get(), s.get());
    }
}
