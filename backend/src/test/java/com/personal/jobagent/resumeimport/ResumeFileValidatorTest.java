package com.personal.jobagent.resumeimport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeFileValidatorTest {

    @Test
    void detectsPdfBySignatureRegardlessOfFilename() {
        byte[] pdf = "%PDF-1.7\n...".getBytes();
        assertThat(ResumeFileValidator.detect(pdf, "resume.txt")).isEqualTo(ResumeFileValidator.DetectedType.PDF);
    }

    @Test
    void detectsDocxBySignature() {
        byte[] docx = new byte[]{'P', 'K', 0x03, 0x04, 0, 0, 0};
        assertThat(ResumeFileValidator.detect(docx, "resume.docx")).isEqualTo(ResumeFileValidator.DetectedType.DOCX);
    }

    @Test
    void ignoresABrowserSuppliedPdfExtensionOnNonPdfBytes() {
        byte[] notAPdf = "just some text".getBytes();
        assertThatThrownBy(() -> ResumeFileValidator.detect(notAPdf, "resume.pdf"))
                .isInstanceOf(UnprocessableResumeException.class);
    }

    @Test
    void rejectsLegacyDocWithAHelpfulMessage() {
        byte[] legacyDoc = new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};
        assertThatThrownBy(() -> ResumeFileValidator.detect(legacyDoc, "resume.doc"))
                .isInstanceOf(UnprocessableResumeException.class)
                .hasMessageContaining(".docx");
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> ResumeFileValidator.detect(new byte[0], "resume.pdf"))
                .isInstanceOf(UnprocessableResumeException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsFileOverTheSizeLimit() {
        byte[] oversized = new byte[(int) ResumeImportLimits.MAX_FILE_SIZE_BYTES + 1];
        System.arraycopy(new byte[]{'%', 'P', 'D', 'F', '-'}, 0, oversized, 0, 5);
        assertThatThrownBy(() -> ResumeFileValidator.detect(oversized, "resume.pdf"))
                .isInstanceOf(UnprocessableResumeException.class)
                .hasMessageContaining("10 MB");
    }
}
