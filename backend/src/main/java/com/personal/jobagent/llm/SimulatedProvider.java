package com.personal.jobagent.llm;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Per architecture doc's validation gate item 4: "Ping endpoint can
 * simulate Gemini until then [real keys are supplied]." This provider is
 * always healthy and always available — it exists specifically so
 * /system/llm/ping and the router's fallback chain are testable before any
 * real API key exists, and so ModelRouter always has SOMETHING to fall
 * back to in the worst case.
 *
 * Deliberately NOT a stand-in for real quality evaluation — benchmark runs
 * (P1-f) should exclude this provider once real ones are configured, since
 * its "completions" are canned, not model output.
 */
@Component
public class SimulatedProvider implements LlmProvider {

    @Override
    public String providerId() {
        return "simulated";
    }

    @Override
    public Set<String> supportedModels() {
        return Set.of("simulated-v1");
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public LlmCompletion complete(LlmCompletionRequest request, Duration timeout) {
        long start = System.currentTimeMillis();
        String prompt = "";
        if (request.messages() != null && !request.messages().isEmpty()) {
            prompt = request.messages().get(request.messages().size() - 1).getOrDefault("content", "");
        }
        String canned;
        if (prompt.contains("cover letter") || prompt.contains("Cover Letter") || prompt.contains("Dear Hiring")) {
            canned = "Dear Hiring Team,\n\nI am writing to express my strong enthusiasm for this role. With my background in software engineering, distributed systems, and modern cloud technologies, I am confident in my ability to deliver immediate value to your engineering team.\n\nThroughout my career, I have consistently focused on building scalable, reliable architectures and driving engineering excellence. My verified technical experience directly matches your core requirements, and I am excited about the opportunity to contribute to your mission.\n\nThank you for considering my application. I look forward to the possibility of discussing how my skills and experience can support your team's success.\n\nSincerely,\nCandidate";
        } else if (prompt.contains("question") || prompt.contains("Question:") || prompt.contains("Why do you want") || prompt.contains("Why are you interested")) {
            canned = "I am deeply interested in this position because your mission aligns directly with my professional experience in building resilient, high-performance systems. Having worked extensively with modern engineering stacks and distributed architectures, I am eager to apply my background to help solve complex technical challenges at scale while collaborating with your world-class team.";
        } else {
            canned = "[simulated response for correlationId=" + request.correlationId() + "]";
        }
        long latency = System.currentTimeMillis() - start;
        return new LlmCompletion(canned, new LlmCompletion.TokenUsage(50, 120), latency, LlmCompletion.FinishReason.STOP);
    }
}
