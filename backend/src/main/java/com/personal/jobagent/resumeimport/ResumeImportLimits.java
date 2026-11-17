package com.personal.jobagent.resumeimport;

import java.time.Duration;

/** Bounds enforced on every resume upload, independent of the client's own limits. */
public final class ResumeImportLimits {

    private ResumeImportLimits() {
    }

    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;
    public static final int MAX_PDF_PAGES = 12;
    /**
     * About 12 dense pages. Over this the document is refused rather than cut
     * short: truncating would silently drop whatever sections come last.
     * Also keeps the extraction prompt (~12k tokens) inside common context windows.
     */
    public static final int MAX_EXTRACTED_CHARS = 50_000;
    /** Below this many characters of extracted text, a PDF is treated as scanned/image-only. */
    public static final int MIN_EXTRACTABLE_CHARS = 50;
    public static final Duration EXTRACTION_TIMEOUT = Duration.ofSeconds(20);
    public static final Duration AI_CALL_TIMEOUT = Duration.ofSeconds(45);
    /** Room for a long CV's full JSON; providers pass this through as max_tokens, so too low truncates it. */
    public static final int AI_MAX_OUTPUT_TOKENS = 8192;
    public static final Duration PREVIEW_TTL = Duration.ofMinutes(30);
    /** One confirm is one transaction; a payload this large is not a resume. */
    public static final int MAX_ITEMS_PER_CONFIRM = 1000;
}
