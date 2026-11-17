package com.personal.jobagent.resumeimport;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * A single clean-format sample resume shared by every resume-import test, plus
 * real PDF/DOCX bytes built from it. {@link com.personal.jobagent.llm.SimulatedProvider}'s
 * profile-extraction branch parses exactly this "Title — Company — YYYY-MM to YYYY-MM"
 * style, so changing this text also means updating that parser (and vice versa).
 */
public final class ResumeImportFixtures {

    private ResumeImportFixtures() {
    }

    public static final String SAMPLE_RESUME_TEXT = String.join("\n",
            "Jordan Rivera",
            "Senior Backend Engineer",
            "jordan.rivera@example.com | +1 555-010-0100 | Austin, TX",
            "linkedin.com/in/jordanrivera",
            "",
            "SUMMARY",
            "Backend engineer focused on distributed systems and reliable APIs.",
            "",
            "EXPERIENCE",
            "Software Engineer — Acme Corp — 2021-01 to 2023-12",
            "Austin, TX",
            "- Built payment processing services handling 10k requests per second",
            "- Led migration from monolith to microservices",
            "",
            "EDUCATION",
            "BSc Computer Science — State University — 2017 to 2021",
            "",
            "SKILLS",
            "Java, Spring Boot, PostgreSQL, Docker",
            "",
            "CERTIFICATIONS",
            "AWS Certified Solutions Architect — Amazon Web Services — 2022");

    public static byte[] samplePdfBytes() throws IOException {
        return pdfBytesFor(SAMPLE_RESUME_TEXT);
    }

    public static byte[] pdfBytesFor(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 10);
                content.newLineAtOffset(50, 740);
                for (String line : text.split("\n")) {
                    content.showText(line.isBlank() ? " " : sanitizeForWinAnsi(line));
                    content.newLineAtOffset(0, -14);
                }
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    public static byte[] sampleDocxBytes() throws IOException {
        return docxBytesFor(SAMPLE_RESUME_TEXT);
    }

    public static byte[] docxBytesFor(String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            for (String line : text.split("\n")) {
                XWPFParagraph paragraph = document.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(line.isBlank() ? " " : line);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    /** PDFBox's base-14 Helvetica only encodes WinAnsi — strips the em dash used in the fixture to a plain hyphen for the PDF rendering only (the DOCX/raw-text fixtures keep the real em dash). */
    private static String sanitizeForWinAnsi(String line) {
        return line.replace('—', '-');
    }

    public static byte[] emptyPdfBytes() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
