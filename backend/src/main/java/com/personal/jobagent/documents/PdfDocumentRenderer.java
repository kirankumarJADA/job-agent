package com.personal.jobagent.documents;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Production PDF writer for Robin documents (tailored CVs, cover letters).
 *
 * <p>Built on Apache PDFBox. Layout is deliberately ATS-friendly: one column,
 * real extractable text, no tables, no images, no multi-column positioning.
 * Text flows across as many A4 pages as it needs, nothing is truncated, long
 * unbroken tokens (URLs) are wrapped by character, and Unicode is preserved by
 * embedding a subset of Liberation Sans (bundled inside the PDFBox jar, SIL OFL).
 * A code point the font cannot draw is replaced with "?" and counted, never
 * silently dropped.
 *
 * <p>Output is deterministic for identical input: no creation dates are
 * written and the trailer /ID is derived from the content, so the same document
 * always produces the same bytes and the same SHA-256.
 */
public final class PdfDocumentRenderer {

    /** Bumped whenever layout changes, so tailoring input hashes change with it. */
    public static final String RENDERER_VERSION = "pdfbox-3.0.3/liberation-sans/layout-1";

    private static final String FONT_RESOURCE = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 54f;
    private static final float FOOTER_SIZE = 8f;

    public enum Kind { TITLE, SUBTITLE, HEADING, SUBHEADING, PARAGRAPH, BULLET, SPACER }

    public record Block(Kind kind, String text) {
        public static Block of(Kind kind, String text) { return new Block(kind, text == null ? "" : text); }
    }

    public record Rendered(byte[] bytes, int pages, int substitutedCharacters) {}

    private record Style(float size, boolean bold, float before, float after, float indent) {}

    private static final Map<Kind, Style> STYLES = Map.of(
            Kind.TITLE, new Style(18f, true, 0f, 4f, 0f),
            Kind.SUBTITLE, new Style(9.5f, false, 0f, 2f, 0f),
            Kind.HEADING, new Style(12.5f, true, 10f, 4f, 0f),
            Kind.SUBHEADING, new Style(10.5f, true, 6f, 1f, 0f),
            Kind.PARAGRAPH, new Style(10f, false, 0f, 2f, 0f),
            Kind.BULLET, new Style(10f, false, 0f, 1.5f, 12f),
            Kind.SPACER, new Style(10f, false, 0f, 0f, 0f));

    public Rendered render(String title, String subject, List<Block> blocks) {
        try (PDDocument document = new PDDocument()) {
            PDType0Font font;
            try (InputStream in = PdfDocumentRenderer.class.getResourceAsStream(FONT_RESOURCE)) {
                if (in == null) throw new IllegalStateException("PDF font resource missing: " + FONT_RESOURCE);
                font = PDType0Font.load(document, in, true);
            }
            Layout layout = new Layout(document, font);
            for (Block block : blocks) layout.add(block);
            layout.finish();

            PDDocumentInformation info = new PDDocumentInformation();
            if (title != null) info.setTitle(title);
            if (subject != null) info.setSubject(subject);
            info.setProducer("Robin " + RENDERER_VERSION);
            document.setDocumentInformation(info);

            addFooters(document, font);
            setDeterministicId(document, title, subject, blocks);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return new Rendered(out.toByteArray(), document.getNumberOfPages(), layout.substituted);
        } catch (IOException e) {
            throw new IllegalStateException("PDF rendering failed", e);
        }
    }

    private void addFooters(PDDocument document, PDType0Font font) throws IOException {
        int total = document.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            PDPage page = document.getPage(i);
            try (PDPageContentStream stream = new PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                String label = "Page " + (i + 1) + " of " + total;
                float width = font.getStringWidth(label) / 1000f * FOOTER_SIZE;
                stream.beginText();
                stream.setFont(font, FOOTER_SIZE);
                stream.newLineAtOffset(PAGE.getWidth() - MARGIN - width, MARGIN / 2f);
                stream.showText(label);
                stream.endText();
            }
        }
    }

    private void setDeterministicId(PDDocument document, String title, String subject, List<Block> blocks) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(RENDERER_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update(String.valueOf(title).getBytes(StandardCharsets.UTF_8));
            digest.update(String.valueOf(subject).getBytes(StandardCharsets.UTF_8));
            for (Block block : blocks) {
                digest.update(block.kind().name().getBytes(StandardCharsets.UTF_8));
                digest.update(block.text().getBytes(StandardCharsets.UTF_8));
            }
            byte[] id = Arrays.copyOf(digest.digest(), 16);
            COSArray array = new COSArray();
            array.add(new COSString(id));
            array.add(new COSString(id));
            document.getDocument().getTrailer().setItem(COSName.ID, array);
        } catch (Exception e) {
            throw new IllegalStateException("PDF id generation failed", e);
        }
    }

    /** Streaming top-to-bottom layout with page breaks. */
    private static final class Layout {
        private final PDDocument document;
        private final PDType0Font font;
        private final Map<Integer, Boolean> drawable = new HashMap<>();
        private PDPageContentStream stream;
        private float y;
        private int substituted;
        private boolean lastWasSpacer = true;

        Layout(PDDocument document, PDType0Font font) throws IOException {
            this.document = document;
            this.font = font;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PAGE);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PAGE.getHeight() - MARGIN;
        }

        void finish() throws IOException { if (stream != null) stream.close(); }

        void add(Block block) throws IOException {
            Style style = STYLES.get(block.kind());
            if (block.kind() == Kind.SPACER) {
                if (!lastWasSpacer) y -= 6f;
                lastWasSpacer = true;
                return;
            }
            String text = sanitize(block.text());
            if (text.isBlank()) return;
            lastWasSpacer = false;
            float leading = style.size() * 1.35f;
            float maxWidth = PAGE.getWidth() - 2 * MARGIN - style.indent();
            List<String> lines = wrap(text, style.size(), maxWidth);

            y -= style.before();
            boolean first = true;
            for (String line : lines) {
                if (y - leading < MARGIN) newPage();
                y -= leading;
                if (block.kind() == Kind.BULLET && first) {
                    drawText("•", MARGIN + 2f, y, style.size(), false);
                }
                drawText(line, MARGIN + style.indent(), y, style.size(), style.bold());
                first = false;
            }
            if (block.kind() == Kind.HEADING) {
                stream.setLineWidth(0.6f);
                stream.moveTo(MARGIN, y - 3f);
                stream.lineTo(PAGE.getWidth() - MARGIN, y - 3f);
                stream.stroke();
                y -= 3f;
            }
            y -= style.after();
        }

        private void drawText(String text, float x, float baseline, float size, boolean bold) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            if (bold) {
                // Liberation Sans Regular is the only bundled face; bold is
                // drawn as fill+stroke, which keeps the text extractable.
                stream.setRenderingMode(RenderingMode.FILL_STROKE);
                stream.setLineWidth(size * 0.035f);
            } else {
                stream.setRenderingMode(RenderingMode.FILL);
            }
            stream.newLineAtOffset(x, baseline);
            stream.showText(text);
            stream.endText();
        }

        private float width(String text, float size) throws IOException {
            return font.getStringWidth(text) / 1000f * size;
        }

        private List<String> wrap(String text, float size, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : text.split(" ")) {
                if (word.isEmpty()) continue;
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (width(candidate, size) <= maxWidth) {
                    current.setLength(0);
                    current.append(candidate);
                    continue;
                }
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                if (width(word, size) <= maxWidth) {
                    current.append(word);
                } else {
                    // An unbroken token wider than the line (a long URL) is
                    // split by character rather than overflowing or dropped.
                    StringBuilder piece = new StringBuilder();
                    for (int i = 0; i < word.length(); ) {
                        int cp = word.codePointAt(i);
                        String next = piece.toString() + new String(Character.toChars(cp));
                        if (width(next, size) > maxWidth && !piece.isEmpty()) {
                            lines.add(piece.toString());
                            piece.setLength(0);
                        }
                        piece.appendCodePoint(cp);
                        i += Character.charCount(cp);
                    }
                    current.append(piece);
                }
            }
            if (!current.isEmpty()) lines.add(current.toString());
            return lines;
        }

        /** Normalises whitespace and replaces only the code points the font cannot draw. */
        private String sanitize(String raw) {
            StringBuilder out = new StringBuilder(raw.length());
            raw.codePoints().forEach(cp -> {
                if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                    out.append(' ');
                } else if (Character.isISOControl(cp)) {
                    // control characters are never content
                } else if (canDraw(cp)) {
                    out.appendCodePoint(cp);
                } else {
                    out.append('?');
                    substituted++;
                }
            });
            return out.toString().replaceAll(" {2,}", " ").trim();
        }

        private boolean canDraw(int cp) {
            return drawable.computeIfAbsent(cp, key -> {
                try {
                    // PDType0Font.encode throws "No glyph for U+..." when the
                    // embedded font cannot draw the code point.
                    return font.encode(new String(Character.toChars(key))).length > 0;
                } catch (Exception e) {
                    return false;
                }
            });
        }
    }
}
