package com.personal.jobagent.benchmark;

import com.personal.jobagent.llm.TaskType;

public record BenchmarkCase(String caseId, TaskType taskType, String input, String expected, String grader) {
}
