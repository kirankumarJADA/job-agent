package com.personal.jobagent.resumeimport;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeTextExtractorTest {

    private final ResumeTextExtractor extractor = new ResumeTextExtractor();

    @Test
    void extractsTextFromARealPdf() throws Exception {
        ResumeTextExtractor.Extracted result = extractor.extract(
                ResumeImportFixtures.samplePdfBytes(), ResumeFileValidator.DetectedType.PDF);
        assertThat(result.text()).contains("Jordan Rivera").contains("Acme Corp");
        assertThat(result.pageCount()).isEqualTo(1);
    }

    @Test
    void extractsTextFromARealDocx() throws Exception {
        ResumeTextExtractor.Extracted result = extractor.extract(
                ResumeImportFixtures.sampleDocxBytes(), ResumeFileValidator.DetectedType.DOCX);
        assertThat(result.text()).contains("Jordan Rivera").contains("Acme Corp");
    }

    @Test
    void rejectsAScannedPdfWithNoExtractableText() throws Exception {
        byte[] blankPdf = ResumeImportFixtures.emptyPdfBytes();
        assertThatThrownBy(() -> extractor.extract(blankPdf, ResumeFileValidator.DetectedType.PDF))
                .isInstanceOf(UnprocessableResumeException.class)
                .hasMessageContaining("scanned");
    }

    @Test
    void rejectsAPasswordProtectedPdf() throws Exception {
        byte[] encrypted;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-pw", "user-pw", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            encrypted = out.toByteArray();
        }
        assertThatThrownBy(() -> extractor.extract(encrypted, ResumeFileValidator.DetectedType.PDF))
                .isInstanceOf(UnprocessableResumeException.class)
                .hasMessageContaining("password");
    }

    @Test
    void rejectsACorruptPdf() {
        byte[] corrupt = "%PDF-1.7\nnot actually a valid pdf body".getBytes();
        assertThatThrownBy(() -> extractor.extract(corrupt, ResumeFileValidator.DetectedType.PDF))
                .isInstanceOf(UnprocessableResumeException.class);
    }

    @Test
    void rejectsAPdfOverThePageLimit() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i <= ResumeImportLimits.MAX_PDF_PAGES; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            bytes = out.toByteArray();
        }
        assertThatThrownBy(() -> extractor.extract(bytes, ResumeFileValidator.DetectedType.PDF))
                .isInstanceOf(UnprocessableResumeException.class)
                .hasMessageContaining("pages");
    }

    @Test
    void rejectsADocumentOverTheTextLimitInsteadOfSilentlyTruncatingIt() throws Exception {
        String line = "Delivered a measurable improvement to a production system used by many customers.\n";
        byte[] huge = ResumeImportFixtures.docxBytesFor(line.repeat(ResumeImportLimits.MAX_EXTRACTED_CHARS / line.length() + 50));
        assertThatThrownBy(() -> extractor.extract(huge, ResumeFileValidator.DetectedType.DOCX))
                .isInstanceOf(UnprocessableResumeException.class)
                .hasMessageContaining("more text");
    }

    @Test
    void rejectsACorruptDocx() {
        byte[] corrupt = new byte[]{'P', 'K', 0x03, 0x04, 1, 2, 3, 4, 5};
        assertThatThrownBy(() -> extractor.extract(corrupt, ResumeFileValidator.DetectedType.DOCX))
                .isInstanceOf(UnprocessableResumeException.class);
    }
}
