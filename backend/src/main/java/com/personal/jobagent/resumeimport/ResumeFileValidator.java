package com.personal.jobagent.resumeimport;

import java.util.Locale;

/**
 * Decides what kind of file was actually uploaded by sniffing its first
 * bytes, never by trusting the browser-supplied filename extension or
 * Content-Type header (per the spec: "validate MIME type without trusting
 * the browser"). The extension is only used for a friendlier error message
 * when it disagrees with the real content.
 */
public final class ResumeFileValidator {

    private ResumeFileValidator() {
    }

    public enum DetectedType { PDF, DOCX }

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};
    /** An empty .docx (no entries yet written) starts with this instead of the normal local-file-header. */
    private static final byte[] ZIP_EMPTY_MAGIC = {'P', 'K', 0x05, 0x06};

    public static DetectedType detect(byte[] bytes, String originalFilename) {
        if (bytes == null || bytes.length == 0) {
            throw new UnprocessableResumeException("The uploaded file is empty.");
        }
        if (bytes.length > ResumeImportLimits.MAX_FILE_SIZE_BYTES) {
            throw new UnprocessableResumeException("The uploaded file is larger than the 10 MB limit.");
        }
        String name = originalFilename == null ? "" : originalFilename.toLowerCase(Locale.ROOT);

        if (startsWith(bytes, PDF_MAGIC)) {
            return DetectedType.PDF;
        }
        if (startsWith(bytes, ZIP_MAGIC) || startsWith(bytes, ZIP_EMPTY_MAGIC)) {
            // A .docx is a ZIP container; a .doc (legacy binary), .zip, .xlsx
            // or .pptx would also start this way, so this is necessary but
            // not sufficient — ResumeTextExtractor rejects anything that
            // does not actually unpack as a Word document.
            return DetectedType.DOCX;
        }
        if (name.endsWith(".doc")) {
            throw new UnprocessableResumeException(
                    "Legacy .doc files are not supported. Please upload a PDF or a .docx file.");
        }
        throw new UnprocessableResumeException(
                "This file is not a readable PDF or Word (.docx) document. "
                        + "Only PDF and DOCX resumes are supported.");
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
