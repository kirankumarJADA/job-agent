package com.personal.jobagent.jobs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillSynonymRegistryTest {

    @Test
    void exactNameReturnsSelf() {
        assertThat(SkillSynonymRegistry.canonicalise("Java")).isEqualTo("java");
    }

    @Test
    void aliasReturnsCanonicaForm() {
        assertThat(SkillSynonymRegistry.canonicalise("ReactJS")).isEqualTo("react");
        assertThat(SkillSynonymRegistry.canonicalise("React.js")).isEqualTo("react");
        assertThat(SkillSynonymRegistry.canonicalise("react js")).isEqualTo("react");
    }

    @Test
    void caseInsensitive() {
        assertThat(SkillSynonymRegistry.canonicalise("PYTHON3")).isEqualTo("python");
        assertThat(SkillSynonymRegistry.canonicalise("NodeJS")).isEqualTo("node.js");
    }

    @Test
    void cloudProviderAliases() {
        assertThat(SkillSynonymRegistry.canonicalise("Amazon Web Services")).isEqualTo("aws");
        assertThat(SkillSynonymRegistry.canonicalise("Google Cloud Platform")).isEqualTo("gcp");
        assertThat(SkillSynonymRegistry.canonicalise("Microsoft Azure")).isEqualTo("azure");
    }

    @Test
    void databaseAliases() {
        assertThat(SkillSynonymRegistry.canonicalise("Postgres")).isEqualTo("postgresql");
        assertThat(SkillSynonymRegistry.canonicalise("psql")).isEqualTo("postgresql");
        assertThat(SkillSynonymRegistry.canonicalise("Mongo")).isEqualTo("mongodb");
    }

    @Test
    void devopsAliases() {
        assertThat(SkillSynonymRegistry.canonicalise("k8s")).isEqualTo("kubernetes");
        assertThat(SkillSynonymRegistry.canonicalise("CI/CD")).isEqualTo("ci/cd");
        assertThat(SkillSynonymRegistry.canonicalise("CICD")).isEqualTo("ci/cd");
    }

    @Test
    void languageAliases() {
        assertThat(SkillSynonymRegistry.canonicalise("csharp")).isEqualTo("c#");
        assertThat(SkillSynonymRegistry.canonicalise("cpp")).isEqualTo("c++");
        assertThat(SkillSynonymRegistry.canonicalise("golang")).isEqualTo("golang");
    }

    @Test
    void areSynonymsRecognisesEquivalentSkills() {
        assertThat(SkillSynonymRegistry.areSynonyms("React", "ReactJS")).isTrue();
        assertThat(SkillSynonymRegistry.areSynonyms("python3", "Python")).isTrue();
        assertThat(SkillSynonymRegistry.areSynonyms("k8s", "Kubernetes")).isTrue();
    }

    @Test
    void areSynonymsReturnsFalseForDifferentSkills() {
        assertThat(SkillSynonymRegistry.areSynonyms("Java", "JavaScript")).isFalse();
        assertThat(SkillSynonymRegistry.areSynonyms("Python", "Ruby")).isFalse();
    }

    @Test
    void unknownSkillReturnsSelfLowerCased() {
        assertThat(SkillSynonymRegistry.canonicalise("ObscureFramework")).isEqualTo("obscureframework");
    }

    @Test
    void nullAndEmptyAreHandled() {
        assertThat(SkillSynonymRegistry.canonicalise(null)).isEqualTo("");
        assertThat(SkillSynonymRegistry.canonicalise("")).isEqualTo("");
        assertThat(SkillSynonymRegistry.canonicalise("  ")).isEqualTo("");
    }

    @Test
    void registryHasReasonableNumberOfGroups() {
        assertThat(SkillSynonymRegistry.groupCount()).isGreaterThan(50);
    }

    @Test
    void mlAliases() {
        assertThat(SkillSynonymRegistry.canonicalise("ML")).isEqualTo("machine learning");
        assertThat(SkillSynonymRegistry.canonicalise("NLP")).isEqualTo("natural language processing");
        assertThat(SkillSynonymRegistry.canonicalise("sklearn")).isEqualTo("scikit-learn");
    }

    @Test
    void frameworkAliases() {
        assertThat(SkillSynonymRegistry.canonicalise("SpringBoot")).isEqualTo("spring boot");
        assertThat(SkillSynonymRegistry.canonicalise("Rails")).isEqualTo("ruby on rails");
        assertThat(SkillSynonymRegistry.canonicalise("ASPNet")).isEqualTo("asp.net");
        assertThat(SkillSynonymRegistry.canonicalise("dotnet")).isEqualTo(".net");
    }
}
