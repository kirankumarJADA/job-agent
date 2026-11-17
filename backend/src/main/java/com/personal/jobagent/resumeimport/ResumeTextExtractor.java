package com.personal.jobagent.resumeimport;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.*;

/**
 * Turns a validated PDF/DOCX byte array into plain text, bounded so a
 * hostile or merely huge file cannot tie up a request thread or blow up the
 * extraction prompt sent to the AI provider. No OCR: a PDF with no
 * extractable text (a scanned image) is reported, not silently accepted.
 */
@Component
public class ResumeTextExtractor {

    public record Extracted(String text, int pageCount) {
    }

    /**
     * Bounded on purpose: cancel(true) cannot stop PDFBox/POI mid-parse, so a
     * timed-out hostile document keeps its thread until it finishes. A fixed
     * pool caps how many of those can burn CPU at once; a request that waits in
     * the queue past its timeout is cancelled before it ever starts.
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread t = new Thread(runnable, "resume-extract");
        t.setDaemon(true);
        return t;
    });

    public Extracted extract(byte[] bytes, ResumeFileValidator.DetectedType type) {
        // PDFBox/POI can both spend an unbounded amount of CPU on a
        // pathological document; running the parse on a bounded future means
        // a slow/hostile file times out the request instead of the worker.
        Future<Extracted> future = executor.submit(() -> switch (type) {
            case PDF -> extractPdf(bytes);
            case DOCX -> extractDocx(bytes);
        });
        try {
            return future.get(ResumeImportLimits.EXTRACTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new UnprocessableResumeException("This document took too long to process. Try a simpler, text-based file.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnprocessableResumeException unprocessable) {
                throw unprocessable;
            }
            throw new UnprocessableResumeException("This document could not be read. It may be corrupt or in an unsupported format.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnprocessableResumeException("Resume processing was interrupted.");
        }
    }

    private Extracted extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw new UnprocessableResumeException("This PDF has no pages.");
            }
            if (pages > ResumeImportLimits.MAX_PDF_PAGES) {
                throw new UnprocessableResumeException(
                        "This PDF has " + pages + " pages; resumes are limited to " + ResumeImportLimits.MAX_PDF_PAGES + " pages.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            String text = stripper.getText(document);
            String normalized = normalize(text);
            if (normalized.length() < ResumeImportLimits.MIN_EXTRACTABLE_CHARS) {
                throw new UnprocessableResumeException(
                        "This PDF does not contain extractable text (it looks like a scanned image). "
                                + "Please upload a text-based PDF or a .docx file.");
            }
            return new Extracted(withinLimit(normalized), pages);
        } catch (InvalidPasswordException e) {
            throw new UnprocessableResumeException("This PDF is password protected. Please upload an unprotected file.");
        } catch (UnprocessableResumeException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UnprocessableResumeException("This PDF could not be read. It may be corrupt.");
        }
    }

    private Extracted extractDocx(byte[] bytes) {
        // POI's ZipSecureFile (used internally by OPCPackage) enforces a
        // minimum inflate ratio and a maximum entry count/size, so a
        // ZIP-bomb-style .docx throws here rather than exhausting memory.
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String line = paragraph.getText();
                if (line != null && !line.isBlank()) {
                    text.append(line).append('\n');
                }
            }
            for (XWPFTable table : document.getTables()) {
                table.getRows().forEach(row -> row.getTableCells().forEach(cell -> {
                    String cellText = cell.getText();
                    if (cellText != null && !cellText.isBlank()) {
                        text.append(cellText).append('\n');
                    }
                }));
            }
            String normalized = normalize(text.toString());
            if (normalized.length() < ResumeImportLimits.MIN_EXTRACTABLE_CHARS) {
                throw new UnprocessableResumeException("This document does not contain enough readable text to import.");
            }
            return new Extracted(withinLimit(normalized), 0);
        } catch (OLE2NotOfficeXmlFileException e) {
            throw new UnprocessableResumeException(
                    "This looks like a legacy .doc file, not .docx. Please upload a PDF or a .docx file.");
        } catch (UnprocessableResumeException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UnprocessableResumeException("This Word document could not be read. It may be corrupt or malformed.");
        }
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    private String withinLimit(String text) {
        if (text.length() > ResumeImportLimits.MAX_EXTRACTED_CHARS) {
            throw new UnprocessableResumeException(
                    "This document contains more text than a resume import can process. Please upload a shorter resume.");
        }
        return text;
    }
}
