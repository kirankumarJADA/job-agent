package com.personal.jobagent.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static org.assertj.core.api.Assertions.assertThat;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the dependency rule from architecture doc §B1:
 *   orchestrator -> engines -> llm / persistence
 *   nothing imports orchestrator except the app bootstrap
 *   llm is a leaf; cross-module communication flows through events or ports
 *
 * "common", "events", and (per §C6, discovered during P1-f implementation)
 * "benchmark" are the exceptions to "llm is a leaf": the architecture
 * explicitly requires the benchmark runner to call ModelRouter directly
 * ("fans out case×model calls through the real router seam"), so llm
 * being reachable from benchmark is correct, not a violation.
 *
 * REVISION NOTE: the original llm_is_a_leaf_module rule (written in P1-a,
 * before any real llm-package classes existed to test it against) checked
 * the wrong subject — it inspected classes OUTSIDE llm rather than classes
 * INSIDE llm, and would have flagged BenchmarkRunner as a violation simply
 * for depending on both ModelRouter and ordinary things like JdbcTemplate.
 * Rewritten below using an explicit positive list of "other business
 * module" packages llm must not depend on, rather than the error-prone
 * not/and predicate composition the correct version would otherwise need.
 * This was caught by careful re-reading, not by running ArchUnit (no
 * Maven Central access in the authoring environment) — treat as
 * high-confidence but not yet execution-verified until the next mvn verify.
 */
@AnalyzeClasses(packages = "com.personal.jobagent", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchModuleBoundaryTest {

    private static final String BASE_PKG = "com.personal.jobagent";

    @ArchTest
    static final ArchRule llm_does_not_depend_on_other_business_modules =
            noClasses().that().resideInAPackage(BASE_PKG + ".llm..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            BASE_PKG + ".security..",
                            BASE_PKG + ".profile..",
                            BASE_PKG + ".preferences..",
                            BASE_PKG + ".jobs..",
                            BASE_PKG + ".benchmark..",
                            BASE_PKG + ".orchestrator..",
                            BASE_PKG + ".audit.."
                    )
                    .as("llm module must not depend on other business modules directly — "
                            + "only common/events, or callers reaching INTO llm (e.g. benchmark per §C6)")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule nothing_but_bootstrap_depends_on_orchestrator =
            noClasses().that().resideOutsideOfPackage(BASE_PKG + ".orchestrator..")
                    .and().resideOutsideOfPackage(BASE_PKG) // JobAgentApplication bootstrap
                    .should().dependOnClassesThat().resideInAPackage(BASE_PKG + ".orchestrator..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule no_module_depends_directly_on_another_business_module =
            layeredArchitecture().consideringOnlyDependenciesInLayers()
                    // Orchestrator doesn't exist yet (Phase 3+) — withOptionalLayers(true)
                    // stops ArchUnit from treating a currently-empty layer as a violation.
                    // Confirmed necessary by a real mvn verify run in P1-a: without this,
                    // the rule fails with "Layer 'X' is empty" for whichever layers have
                    // no classes yet, which is a false positive, not a real violation.
                    .withOptionalLayers(true)
                    .layer("Common").definedBy(BASE_PKG + ".common..")
                    .layer("Events").definedBy(BASE_PKG + ".events..")
                    .layer("Security").definedBy(BASE_PKG + ".security..")
                    .layer("System").definedBy(BASE_PKG + ".system..")
                    .layer("Audit").definedBy(BASE_PKG + ".audit..")
                    .layer("Profile").definedBy(BASE_PKG + ".profile..")
                    .layer("Preferences").definedBy(BASE_PKG + ".preferences..")
                    .layer("Jobs").definedBy(BASE_PKG + ".jobs..")
                    .layer("Benchmark").definedBy(BASE_PKG + ".benchmark..")
                    .layer("Orchestrator").definedBy(BASE_PKG + ".orchestrator..")
                    .layer("Llm").definedBy(BASE_PKG + ".llm..")
                    // "Orchestrator may only be reached from bootstrap" is NOT expressed
                    // here: ArchUnit's mayOnlyBeAccessedByLayers(...) takes only names of
                    // OTHER DECLARED LAYERS. The bootstrap class (JobAgentApplication)
                    // lives directly in the base package, is not itself a declared layer,
                    // and passing a made-up placeholder name here would reference an
                    // undefined layer — which throws at rule-evaluation time rather than
                    // enforcing anything. That specific rule is instead enforced correctly
                    // above by nothing_but_bootstrap_depends_on_orchestrator, which can
                    // exclude the base package directly via resideOutsideOfPackage(BASE_PKG).
                    //
                    // Llm may be reached by System (the /system/llm/ping diagnostic
                    // endpoint) and Benchmark (§C6's "real router seam" requirement,
                    // confirmed during P1-f implementation) — Orchestrator will be added
                    // here once it exists in a later phase and actually calls into Llm.
                    .whereLayer("Llm").mayOnlyBeAccessedByLayers("System", "Benchmark")
                    .as("business modules communicate via events/ports, not direct imports of each other");

    // Smoke test: make sure the import scope itself is non-empty, so a
    // misconfigured @AnalyzeClasses silently "passing" on zero classes can't
    // hide behind allowEmptyShould(true) above forever as real modules land.
    @ArchTest
    static void classes_are_actually_being_analyzed(JavaClasses classes) {
        assertThat(classes.size())
                .as("ArchUnit found zero classes under " + BASE_PKG
                        + " — check the package/classpath configuration, this rule set is not actually running.")
                .isGreaterThan(0);
    }
}
