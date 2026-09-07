package com.personal.jobagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Single deployable, modular monolith (see docs/adr for the boundary
 * decision). Package-per-module under com.personal.jobagent.* with
 * boundaries enforced by ArchModuleBoundaryTest, not by process separation.
 *
 * @EnableScheduling added in P1-c for OutboxProcessor's dispatch/reconcile
 * loops — this is the Scheduler component from the architecture's topology
 * diagram (§B1), whose ownership was unassigned in the original P1-a..g
 * build order (see architectural-conflict-review.md §2) and resolved to
 * P1-c during implementation.
 *
 * @EnableAsync added in P1-f so BenchmarkRunner can execute in the
 * background, matching POST /benchmarks/runs's 202-Accepted contract.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class JobAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobAgentApplication.class, args);
    }
}
