package com.personal.jobagent.resumeimport.dto;

import java.util.List;

public record ResumeImportConfirmResponse(
        int added,
        int replaced,
        int skipped,
        List<String> warnings
) {
}
