package com.personal.jobagent.resume;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

class CvArtifactServiceTest {
    @Test
    void rendersDeterministicPdfBytes() throws Exception {
        CvArtifactService service = new CvArtifactService();
        byte[] first = service.renderPdf("# Backend Engineer\n\n## Skills\nJava, Spring\n");
        byte[] second = service.renderPdf("# Backend Engineer\n\n## Skills\nJava, Spring\n");
        assertThat(first).startsWith("%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
        assertThat(first).contains("%%EOF".getBytes(StandardCharsets.US_ASCII));
        assertThat(first).isEqualTo(second);
        assertThat(MessageDigest.getInstance("SHA-256").digest(first)).isNotEmpty();
    }
}
