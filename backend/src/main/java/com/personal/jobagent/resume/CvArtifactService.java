package com.personal.jobagent.resume;

import com.personal.jobagent.documents.MarkdownBlocks;
import com.personal.jobagent.documents.PdfDocumentRenderer;
import org.springframework.stereotype.Service;

/**
 * Renders tailored-CV markdown to PDF bytes.
 *
 * <p>Phase 8.1 replaced the previous hand-written serializer, which replaced
 * every non-ASCII character with "?", stopped after 51 lines on one page, and
 * wrote literal "\n" sequences into the content stream so the text could not be
 * extracted (shown by CvArtifactServiceTest before the replacement). Rendering
 * now goes through {@link PdfDocumentRenderer} (Apache PDFBox).
 */
@Service
public class CvArtifactService {

    private final PdfDocumentRenderer renderer = new PdfDocumentRenderer();

    public byte[] renderPdf(String markdown) {
        return renderWithMetadata(markdown, null).bytes();
    }

    public PdfDocumentRenderer.Rendered renderWithMetadata(String markdown, String subject) {
        var blocks = MarkdownBlocks.parse(markdown);
        String title = blocks.stream().filter(b -> b.kind() == PdfDocumentRenderer.Kind.TITLE)
                .map(PdfDocumentRenderer.Block::text).findFirst().orElse("Tailored CV");
        return renderer.render(title, subject, blocks);
    }

    public String rendererVersion() {
        return PdfDocumentRenderer.RENDERER_VERSION;
    }
}
