package com.personal.jobagent.resume;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

class CvArtifactServiceTest {

    private final CvArtifactService service = new CvArtifactService();

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static int pages(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    @Test
    void rendersDeterministicPdfBytes() throws Exception {
        byte[] first = service.renderPdf("# Backend Engineer\n\n## Skills\nJava, Spring\n");
        byte[] second = service.renderPdf("# Backend Engineer\n\n## Skills\nJava, Spring\n");
        assertThat(first).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        assertThat(first).isEqualTo(second);
        assertThat(MessageDigest.getInstance("SHA-256").digest(first)).isNotEmpty();
    }

    @Test
    void isAStructurallyReadablePdf() throws Exception {
        byte[] pdf = service.renderPdf("# Ada Lovelace\n\n## Skills\nJava, Spring\n");
        assertThat(pages(pdf)).isEqualTo(1);
        assertThat(text(pdf)).contains("Ada Lovelace").contains("Skills").contains("Java, Spring");
    }

    @Test
    void longContentFlowsOntoMorePagesWithoutTruncation() throws Exception {
        StringBuilder markdown = new StringBuilder("# Long CV\n\n## Experience\n");
        for (int i = 1; i <= 160; i++) markdown.append("- Delivered item number ").append(i).append(" for the platform team\n");
        markdown.append("\nFINAL-LINE-MARKER\n");

        byte[] pdf = service.renderPdf(markdown.toString());

        assertThat(pages(pdf)).isGreaterThan(1);
        String text = text(pdf);
        assertThat(text).contains("item number 1 ").contains("item number 160 ").contains("FINAL-LINE-MARKER");
    }

    @Test
    void preservesUnicodeAndTypographicPunctuation() throws Exception {
        byte[] pdf = service.renderPdf("# José Müller-Łukasz\n\nZürich — “quoted” café, naïve résumé, 50 € budget, Ångström\n");
        String text = text(pdf);
        assertThat(text).contains("José Müller-Łukasz").contains("Zürich").contains("“quoted”")
                .contains("café").contains("résumé").contains("€").contains("Ångström");
        assertThat(text).doesNotContain("?");
    }

    @Test
    void wrapsAnUnbrokenVeryLongTokenInsteadOfDroppingIt() throws Exception {
        String token = "https://example.test/" + "a".repeat(300);
        String text = text(service.renderPdf("# Links\n\n" + token + "\n")).replaceAll("\\s+", "");
        assertThat(text).contains(token);
    }
}
