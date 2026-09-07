package com.personal.jobagent.llm;

import com.personal.jobagent.common.UuidV7;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Set;

/**
 * GET /api/v1/system/llm/ping?task=&forceFallback=true per docs/contracts/api.md.
 * This is the endpoint the Phase 1 DoD calls out for the forced-failover
 * demo. Without real NIM/GEMINI keys configured (the case throughout this
 * build pass), nim and gemini genuinely report NOT_CONFIGURED and the
 * router genuinely falls back to the simulated provider — so hitting this
 * endpoint right now already demonstrates real failover, not a contrived
 * one. forceFallback=true additionally skips whichever provider would
 * otherwise have succeeded, so the failover path is demonstrable even
 * once real keys ARE configured.
 */
@RestController
public class LlmPingController {

    private final ModelRouter modelRouter;

    public LlmPingController(ModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    @GetMapping("/api/v1/system/llm/ping")
    public RoutingTrace ping(@RequestParam(defaultValue = "JOB_CLASSIFICATION") TaskType task,
                              @RequestParam(defaultValue = "false") boolean forceFallback) {
        LlmCompletionRequest request = LlmCompletionRequest.simple(
                "ping-test", "ping", UuidV7.generate());

        Set<String> forceSkip = forceFallback ? Set.of("PRIMARY", "nim") : Set.of();

        ModelRouter.ExecutionResult result = modelRouter.execute(task, request, Duration.ofSeconds(10), forceSkip);
        return result.trace();
    }
}
