package com.personal.jobagent.resume;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class CvArtifactService {
    public byte[] renderPdf(String markdown) {
        String safe = markdown == null ? "" : markdown.replaceAll("[^\\x20-\\x7E\\r\\n\\t]", "?");
        List<String> lines = new ArrayList<>();
        for (String source : safe.split("\\R", -1)) {
            String line = source.replaceFirst("^#+\\s*", "").replace("—", "-");
            if (line.isBlank()) lines.add("");
            else for (int start = 0; start < line.length(); start += 92) lines.add(line.substring(start, Math.min(start + 92, line.length())));
        }
        StringBuilder stream = new StringBuilder("BT\\n/F1 10 Tf\\n50 760 Td\\n");
        int lineCount = 0;
        for (String line : lines) {
            if (lineCount > 0) stream.append("0 -14 Td\\n");
            stream.append("(").append(escape(line)).append(") Tj\\n");
            lineCount++;
            if (lineCount >= 51) break;
        }
        stream.append("ET\\n");
        byte[] streamBytes = stream.toString().getBytes(StandardCharsets.US_ASCII);
        String object1 = "1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\\n";
        String object2 = "2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\\n";
        String object3 = "3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>endobj\\n";
        String object4 = "4 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>endobj\\n";
        byte[] object5Prefix = "5 0 obj<< /Length ".getBytes(StandardCharsets.US_ASCII);
        byte[] object5Suffix = " >>stream\\n".getBytes(StandardCharsets.US_ASCII);
        byte[] object5End = "endstream\\nendobj\\n".getBytes(StandardCharsets.US_ASCII);

        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        write(output, "%PDF-1.4\\n%\u00E2\u00E3\u00CF\u00D3\\n");
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (String object : List.of(object1, object2, object3, object4)) {
            offsets.add(output.size()); write(output, object);
        }
        offsets.add(output.size());
        output.writeBytes(object5Prefix); write(output, String.valueOf(streamBytes.length)); output.writeBytes(object5Suffix);
        output.writeBytes(streamBytes); output.writeBytes(object5End);
        int xref = output.size();
        write(output, "xref\\n0 6\\n0000000000 65535 f \\n");
        for (int i = 1; i < offsets.size(); i++) write(output, String.format("%010d 00000 n \\n", offsets.get(i)));
        write(output, "trailer<< /Size 6 /Root 1 0 R >>\\nstartxref\\n" + xref + "\\n%%EOF\\n");
        return output.toByteArray();
    }

    private String escape(String text) { return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)"); }
    private void write(java.io.ByteArrayOutputStream output, String text) { output.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1)); }
}
