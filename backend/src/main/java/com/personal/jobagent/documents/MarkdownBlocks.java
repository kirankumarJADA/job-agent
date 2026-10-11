package com.personal.jobagent.documents;

import com.personal.jobagent.documents.PdfDocumentRenderer.Block;
import com.personal.jobagent.documents.PdfDocumentRenderer.Kind;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the small markdown dialect Robin generates (CVs and cover letters)
 * into layout blocks. Supported: "# " title, plain lines directly under the
 * title (contact/headline), "## " section heading, "### " entry heading,
 * "- " / "* " / "• " bullets, blank-line separation, and "**bold**" markers
 * (stripped; the PDF has one face). Anything else is a paragraph, so no line of
 * input is ever lost.
 */
public final class MarkdownBlocks {
    private MarkdownBlocks() {}

    public static List<Block> parse(String markdown) {
        List<Block> blocks = new ArrayList<>();
        if (markdown == null) return blocks;
        boolean inTitleHeader = false;
        for (String raw : markdown.split("\\R", -1)) {
            String line = raw.strip().replace("**", "");
            if (line.isEmpty()) {
                inTitleHeader = false;
                blocks.add(Block.of(Kind.SPACER, ""));
            } else if (line.startsWith("### ")) {
                inTitleHeader = false;
                blocks.add(Block.of(Kind.SUBHEADING, line.substring(4)));
            } else if (line.startsWith("## ")) {
                inTitleHeader = false;
                blocks.add(Block.of(Kind.HEADING, line.substring(3)));
            } else if (line.startsWith("# ")) {
                inTitleHeader = true;
                blocks.add(Block.of(Kind.TITLE, line.substring(2)));
            } else if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) {
                inTitleHeader = false;
                blocks.add(Block.of(Kind.BULLET, line.substring(2)));
            } else if (inTitleHeader) {
                blocks.add(Block.of(Kind.SUBTITLE, line));
            } else {
                blocks.add(Block.of(Kind.PARAGRAPH, line));
            }
        }
        return blocks;
    }
}
