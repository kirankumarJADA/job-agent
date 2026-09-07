package com.personal.jobagent.llm;

import java.util.List;

public record RoutingTrace(TaskType task, String chosenProvider, String chosenReason, List<ModelAttempt> attempts) {

    public record ModelAttempt(String providerId, long latencyMs, boolean ok, String errorClass) {
    }
}
