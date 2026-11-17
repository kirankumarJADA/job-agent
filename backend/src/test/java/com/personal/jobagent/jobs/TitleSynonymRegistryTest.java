package com.personal.jobagent.jobs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TitleSynonymRegistryTest {

    @Test
    void canonicaliseMapsVariationsToPrimary() {
        assertThat(TitleSynonymRegistry.canonicalise("Software Engineer")).isEqualTo("software engineer");
        assertThat(TitleSynonymRegistry.canonicalise("Software Developer")).isEqualTo("software engineer");
        assertThat(TitleSynonymRegistry.canonicalise("SWE")).isEqualTo("software engineer");
        assertThat(TitleSynonymRegistry.canonicalise("Software Development Engineer")).isEqualTo("software engineer");

        assertThat(TitleSynonymRegistry.canonicalise("Java Developer")).isEqualTo("backend engineer");
        assertThat(TitleSynonymRegistry.canonicalise("Server Side Engineer")).isEqualTo("backend engineer");

        assertThat(TitleSynonymRegistry.canonicalise("DevOps Engineer")).isEqualTo("platform engineer");
        assertThat(TitleSynonymRegistry.canonicalise("Site Reliability Engineer")).isEqualTo("platform engineer");
        assertThat(TitleSynonymRegistry.canonicalise("SRE")).isEqualTo("platform engineer");
    }

    @Test
    void areSynonymsIdentifiesTrueEquivalents() {
        assertThat(TitleSynonymRegistry.areSynonyms("Software Engineer", "Software Developer")).isTrue();
        assertThat(TitleSynonymRegistry.areSynonyms("Backend Engineer", "Java Developer")).isTrue();
        assertThat(TitleSynonymRegistry.areSynonyms("Platform Engineer", "DevOps Engineer")).isTrue();
        assertThat(TitleSynonymRegistry.areSynonyms("Full Stack Engineer", "Fullstack Developer")).isTrue();

        assertThat(TitleSynonymRegistry.areSynonyms("Frontend Engineer", "Backend Engineer")).isFalse();
        assertThat(TitleSynonymRegistry.areSynonyms("Data Engineer", "Software Engineer")).isFalse();
    }

    @Test
    void expandReturnsAllSynonyms() {
        List<String> expanded = TitleSynonymRegistry.expand("Software Engineer");
        assertThat(expanded).contains("Software Engineer", "software developer", "programmer", "swe");

        List<String> unknown = TitleSynonymRegistry.expand("Chief Astronaut Officer");
        assertThat(unknown).containsExactly("Chief Astronaut Officer");
    }

    @Test
    void expandTitlesDeduplicatesAcrossMultiplePreferences() {
        List<String> expanded = TitleSynonymRegistry.expandTitles(List.of("Software Engineer", "Software Developer"));
        assertThat(expanded).contains("Software Engineer", "software developer", "programmer", "swe");
        // Ensure no duplicated forms
        long devCount = expanded.stream().filter(s -> s.equalsIgnoreCase("software developer")).count();
        assertThat(devCount).isEqualTo(1);
    }

    @Test
    void expandQueryVariantsLimitsExpansionSize() {
        List<String> variants = TitleSynonymRegistry.expandQueryVariants("backend engineer");
        assertThat(variants).isNotEmpty();
        assertThat(variants.get(0)).isEqualTo("backend engineer");
        assertThat(variants.size()).isLessThanOrEqualTo(4);

        List<String> unknown = TitleSynonymRegistry.expandQueryVariants("quantum mechanics");
        assertThat(unknown).containsExactly("quantum mechanics");
    }
}
