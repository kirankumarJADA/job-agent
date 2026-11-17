package com.personal.jobagent.resumeimport.dto;

import java.util.List;

public record ExtractedSummary(String professionalSummary, String careerFocus, List<String> specializations, boolean sourceVerified) {
}
