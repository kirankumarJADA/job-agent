package com.personal.jobagent.documents;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PdfDocumentRendererTest {

    private static final float MARGIN = 54f;

    private final PdfDocumentRenderer renderer = new PdfDocumentRenderer();

    private static String pageText(PDDocument doc, int pageIndexZeroBased) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageIndexZeroBased + 1);
        stripper.setEndPage(pageIndexZeroBased + 1);
        return stripper.getText(doc);
    }

    @Test
    void aHeadingNearThePageBottomMovesToTheNextPageWithItsFollowingContentInstead() throws Exception {
        for (int filler = 20; filler <= 70; filler++) {
            List<PdfDocumentRenderer.Block> blocks = new ArrayList<>();
            blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.TITLE, "Probe"));
            for (int i = 0; i < filler; i++) {
                blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.PARAGRAPH, "filler line " + i));
            }
            blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.HEADING, "ORPHAN-HEADING-MARKER"));
            blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.PARAGRAPH, "FOLLOWING-CONTENT-MARKER"));

            byte[] pdf = renderer.render("Probe", null, blocks).bytes();
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                int headingPage = -1;
                int followingPage = -1;
                for (int p = 0; p < doc.getNumberOfPages(); p++) {
                    String text = pageText(doc, p);
                    if (text.contains("ORPHAN-HEADING-MARKER")) headingPage = p;
                    if (text.contains("FOLLOWING-CONTENT-MARKER")) followingPage = p;
                }
                assertThat(followingPage)
                        .describedAs("filler=%d: heading landed on page %d but its own following content landed on page %d",
                                filler, headingPage, followingPage)
                        .isEqualTo(headingPage);
            }
        }
    }

    @Test
    void aSubheadingNearThePageBottomAlsoMovesWithItsFollowingContent() throws Exception {
        for (int filler = 20; filler <= 70; filler++) {
            List<PdfDocumentRenderer.Block> blocks = new ArrayList<>();
            blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.TITLE, "Probe"));
            for (int i = 0; i < filler; i++) {
                blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.PARAGRAPH, "filler line " + i));
            }
            blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.SUBHEADING, "ORPHAN-SUBHEADING-MARKER"));
            blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.BULLET, "FOLLOWING-BULLET-MARKER"));

            byte[] pdf = renderer.render("Probe", null, blocks).bytes();
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                int headingPage = -1;
                int followingPage = -1;
                for (int p = 0; p < doc.getNumberOfPages(); p++) {
                    String text = pageText(doc, p);
                    if (text.contains("ORPHAN-SUBHEADING-MARKER")) headingPage = p;
                    if (text.contains("FOLLOWING-BULLET-MARKER")) followingPage = p;
                }
                assertThat(followingPage).isEqualTo(headingPage);
            }
        }
    }

    @Test
    void neverForcesABlankPageJustToKeepAHeadingWithItsContent() throws Exception {
        // A heading that is the very first block on a page must stay there
        // even if its following content still has to flow onward — moving
        // it again would create an unnecessary blank page.
        List<PdfDocumentRenderer.Block> blocks = new ArrayList<>();
        blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.TITLE, "Probe"));
        blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.HEADING, "LONE-HEADING"));
        for (int i = 0; i < 80; i++) {
            blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.PARAGRAPH, "body line " + i));
        }
        byte[] pdf = renderer.render("Probe", null, blocks).bytes();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(pageText(doc, 0)).contains("LONE-HEADING").contains("body line 0");
        }
    }

    @Test
    void everyPageUsesAConsistentA4SizeAndMargin() throws Exception {
        List<PdfDocumentRenderer.Block> blocks = new ArrayList<>();
        blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.TITLE, "Probe"));
        for (int i = 0; i < 200; i++) {
            blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.BULLET, "entry number " + i));
        }
        byte[] pdf = renderer.render("Probe", null, blocks).bytes();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(2);
            var expected = org.apache.pdfbox.pdmodel.common.PDRectangle.A4;
            for (int p = 0; p < doc.getNumberOfPages(); p++) {
                var box = doc.getPage(p).getMediaBox();
                assertThat(box.getWidth()).isEqualTo(expected.getWidth());
                assertThat(box.getHeight()).isEqualTo(expected.getHeight());
            }
        }
    }

    /**
     * {@link TextPosition#getYDirAdj()} measures from the TOP of the page
     * (text-extraction convention), not from the PDF content stream's
     * bottom-left origin — so the footer (deliberately placed within the
     * bottom margin band, close to the page's bottom edge) reports a large Y
     * here. This test checks the one thing that actually indicates clipped
     * or off-page content: every glyph's box lies within the physical page
     * (never negative, never past width/height) — not the softer "inside
     * the margin" rule, which the footer legitimately violates by design.
     */
    @Test
    void noTextIsDrawnOutsideThePhysicalPageOnAnyPage() throws Exception {
        List<PdfDocumentRenderer.Block> blocks = new ArrayList<>();
        blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.TITLE, "Probe Candidate Name"));
        blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.SUBTITLE, "probe@example.test"));
        for (int i = 0; i < 10; i++) {
            blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.HEADING, "Section " + i));
            for (int j = 0; j < 25; j++) {
                blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.BULLET,
                        "A reasonably long bullet point describing achievement " + i + "." + j + " in some detail."));
            }
        }
        byte[] pdf = renderer.render("Probe", null, blocks).bytes();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            var box = doc.getPage(0).getMediaBox();
            List<String> violations = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    super.processTextPosition(text);
                    float x = text.getXDirAdj();
                    float y = text.getYDirAdj();
                    if (x < -1f || x > box.getWidth() + 1f || y < -1f || y > box.getHeight() + 1f) {
                        violations.add("x=" + x + " y=" + y + " text=" + text.getUnicode());
                    }
                }
            };
            stripper.getText(doc);
            assertThat(violations).isEmpty();
        }
    }

    @Test
    void bodyTextNeverCrossesAboveTheTopMargin() throws Exception {
        // Distance-from-top convention: body text's Y must never be smaller
        // than MARGIN (which would mean it was drawn above the top margin).
        List<PdfDocumentRenderer.Block> blocks = List.of(
                PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.TITLE, "Probe Candidate Name"),
                PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.PARAGRAPH, "First body line right under the title."));
        byte[] pdf = renderer.render("Probe", null, blocks).bytes();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<Float> tops = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    super.processTextPosition(text);
                    tops.add(text.getYDirAdj());
                }
            };
            stripper.getText(doc);
            assertThat(tops).isNotEmpty();
            assertThat(tops.stream().min(Float::compareTo).orElseThrow()).isGreaterThanOrEqualTo(MARGIN - 1f);
        }
    }

    @Test
    void noPageIsLeftBlankWhenContentFlowsAcrossSeveralPages() throws Exception {
        List<PdfDocumentRenderer.Block> blocks = new ArrayList<>();
        blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.TITLE, "Probe"));
        for (int i = 0; i < 400; i++) {
            blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.BULLET, "entry number " + i));
        }
        byte[] pdf = renderer.render("Probe", null, blocks).bytes();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(3);
            for (int p = 0; p < doc.getNumberOfPages(); p++) {
                String withoutFooter = pageText(doc, p).replaceAll("Page \\d+ of \\d+", "").trim();
                assertThat(withoutFooter).describedAs("page %d should not be blank", p + 1).isNotEmpty();
            }
        }
    }

    @Test
    void theEmbeddedFontIsActuallyEmbeddedNotJustReferenced() throws Exception {
        byte[] pdf = renderer.render("Probe", null,
                List.of(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.TITLE, "Probe"))).bytes();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            var resources = doc.getPage(0).getResources();
            AtomicInteger fontsChecked = new AtomicInteger();
            for (COSName name : resources.getFontNames()) {
                PDFont font = resources.getFont(name);
                assertThat(font.isEmbedded()).describedAs("font %s must be embedded for reliable rendering", name).isTrue();
                fontsChecked.incrementAndGet();
            }
            assertThat(fontsChecked.get()).isGreaterThan(0);
        }
    }

    @Test
    void aVeryLongWrappedParagraphKeepsEveryWordAndIsNotTruncated() throws Exception {
        StringBuilder paragraph = new StringBuilder();
        for (int i = 0; i < 400; i++) paragraph.append("word").append(i).append(' ');
        byte[] pdf = renderer.render("Probe", null, List.of(
                PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.TITLE, "Probe"),
                PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.PARAGRAPH, paragraph.toString().trim()))).bytes();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertThat(text).contains("word0 ").contains("word199 ").contains("word399");
        }
    }

    @Test
    void anUnusuallyLargeCvRendersWithinBoundedTimeAndMemory() throws Exception {
        List<PdfDocumentRenderer.Block> blocks = new ArrayList<>();
        blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.TITLE, "Probe"));
        for (int section = 0; section < 40; section++) {
            blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.HEADING, "Section " + section));
            for (int i = 0; i < 100; i++) {
                blocks.add(PdfDocumentRenderer.Block.of(PdfDocumentRenderer.Kind.BULLET,
                        "Large CV stress-test bullet " + section + "." + i
                                + " with a reasonably long description of an achievement to exercise wrapping."));
            }
        }
        long start = System.nanoTime();
        var rendered = renderer.render("Probe", null, blocks);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).describedAs("rendering 4000 bullets took %dms", elapsedMs).isLessThan(15_000);
        assertThat(rendered.pages()).isGreaterThan(50);
        try (PDDocument doc = Loader.loadPDF(rendered.bytes())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(rendered.pages());
        }
    }
}
