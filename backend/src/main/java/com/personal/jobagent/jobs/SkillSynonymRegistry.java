package com.personal.jobagent.jobs;

import java.util.*;

/**
 * Curated, deterministic mapping of common technology skill aliases.
 * <p>
 * Each synonym group maps every variant to a single canonical form
 * (lower-case). This avoids LLM calls for the most common mismatches:
 * "React" vs "ReactJS", "AWS" vs "Amazon Web Services", etc.
 * <p>
 * Thread-safe and immutable after construction.
 */
public final class SkillSynonymRegistry {

    /** alias (lower-case) → canonical form (lower-case). */
    private static final Map<String, String> ALIAS_MAP;

    private SkillSynonymRegistry() {}

    static {
        // Each group: first element is the canonical form.
        List<List<String>> groups = List.of(
                // ── JavaScript ecosystem ──
                List.of("javascript", "js", "ecmascript", "es6", "es2015", "es2016", "es2017"),
                List.of("typescript", "ts"),
                List.of("react", "reactjs", "react.js", "react js"),
                List.of("angular", "angularjs", "angular.js", "angular js"),
                List.of("vue", "vuejs", "vue.js", "vue js"),
                List.of("next.js", "nextjs", "next js", "next"),
                List.of("node.js", "nodejs", "node js", "node"),
                List.of("express", "expressjs", "express.js"),
                List.of("jquery", "j query"),

                // ── Python ecosystem ──
                List.of("python", "python3", "python 3", "python2", "python 2"),
                List.of("django", "django framework"),
                List.of("flask", "flask framework"),
                List.of("fastapi", "fast api"),
                List.of("pandas", "python pandas"),
                List.of("numpy", "python numpy"),
                List.of("scikit-learn", "sklearn", "scikit learn"),
                List.of("pytorch", "torch", "py torch"),
                List.of("tensorflow", "tf", "tensor flow"),

                // ── Java ecosystem ──
                List.of("java", "java se", "core java"),
                List.of("spring", "spring framework"),
                List.of("spring boot", "springboot"),
                List.of("hibernate", "hibernate orm"),
                List.of("kotlin", "kotlin/jvm"),

                // ── Cloud providers ──
                List.of("aws", "amazon web services", "amazon aws"),
                List.of("azure", "microsoft azure", "ms azure"),
                List.of("gcp", "google cloud", "google cloud platform"),

                // ── AWS services ──
                List.of("s3", "amazon s3", "aws s3"),
                List.of("ec2", "amazon ec2", "aws ec2"),
                List.of("lambda", "aws lambda", "amazon lambda"),
                List.of("dynamodb", "amazon dynamodb", "aws dynamodb", "dynamo db"),
                List.of("sqs", "amazon sqs", "aws sqs"),
                List.of("sns", "amazon sns", "aws sns"),
                List.of("ecs", "amazon ecs", "aws ecs"),
                List.of("eks", "amazon eks", "aws eks"),
                List.of("rds", "amazon rds", "aws rds"),
                List.of("cloudformation", "aws cloudformation", "cloud formation"),

                // ── Databases ──
                List.of("postgresql", "postgres", "psql", "pgsql"),
                List.of("mysql", "my sql"),
                List.of("mongodb", "mongo", "mongo db"),
                List.of("redis", "redis cache"),
                List.of("elasticsearch", "elastic search", "elastic", "es"),
                List.of("cassandra", "apache cassandra"),
                List.of("sql server", "mssql", "microsoft sql server", "ms sql"),

                // ── DevOps / Infrastructure ──
                List.of("docker", "docker containers"),
                List.of("kubernetes", "k8s", "kube"),
                List.of("terraform", "tf", "hashicorp terraform"),
                List.of("ansible", "ansible automation"),
                List.of("jenkins", "jenkins ci"),
                List.of("github actions", "gh actions"),
                List.of("gitlab ci", "gitlab ci/cd"),
                List.of("ci/cd", "cicd", "ci cd", "continuous integration"),

                // ── Languages ──
                List.of("c#", "csharp", "c sharp"),
                List.of("c++", "cpp", "cplusplus"),
                List.of("golang", "go lang", "go"),
                List.of("rust", "rust lang", "rustlang"),
                List.of("ruby", "ruby lang"),
                List.of("ruby on rails", "rails", "ror"),
                List.of("swift", "swift lang"),
                List.of("objective-c", "objc", "obj-c"),
                List.of("r", "r language", "r lang"),
                List.of("scala", "scala lang"),
                List.of("php", "php language"),
                List.of("perl", "perl language"),

                // ── .NET ecosystem ──
                List.of(".net", "dotnet", "dot net"),
                List.of("asp.net", "aspnet", "asp net"),
                List.of(".net core", "dotnet core", "netcore"),

                // ── Data / ML ──
                List.of("machine learning", "ml"),
                List.of("deep learning", "dl"),
                List.of("natural language processing", "nlp"),
                List.of("computer vision", "cv"),
                List.of("artificial intelligence", "ai"),
                List.of("large language models", "llm", "llms"),
                List.of("data science", "data-science"),
                List.of("data engineering", "data-engineering"),
                List.of("apache spark", "spark", "pyspark"),
                List.of("apache kafka", "kafka"),
                List.of("apache airflow", "airflow"),
                List.of("apache flink", "flink"),
                List.of("hadoop", "apache hadoop"),
                List.of("power bi", "powerbi", "power-bi"),
                List.of("tableau", "tableau desktop"),

                // ── Frontend / UI ──
                List.of("html", "html5"),
                List.of("css", "css3"),
                List.of("sass", "scss"),
                List.of("tailwind", "tailwind css", "tailwindcss"),
                List.of("bootstrap", "bootstrap css"),
                List.of("webpack", "web pack"),
                List.of("graphql", "graph ql"),
                List.of("rest", "rest api", "restful", "restful api"),

                // ── Mobile ──
                List.of("react native", "react-native", "rn"),
                List.of("flutter", "flutter sdk"),
                List.of("android", "android sdk", "android development"),
                List.of("ios", "ios development"),

                // ── Testing ──
                List.of("junit", "j unit"),
                List.of("jest", "jest testing"),
                List.of("cypress", "cypress.io"),
                List.of("selenium", "selenium webdriver"),
                List.of("playwright", "ms playwright"),

                // ── Version control ──
                List.of("git", "git scm"),
                List.of("github", "git hub"),
                List.of("gitlab", "git lab"),
                List.of("bitbucket", "bit bucket"),

                // ── Methodologies ──
                List.of("agile", "agile methodology"),
                List.of("scrum", "scrum framework"),
                List.of("microservices", "micro services", "micro-services"),
                List.of("event-driven architecture", "eda", "event driven"),
                List.of("domain-driven design", "ddd"),
                List.of("test-driven development", "tdd"),

                // ── Security ──
                List.of("oauth", "oauth2", "oauth 2.0"),
                List.of("jwt", "json web token", "json web tokens"),
                List.of("ssl", "tls", "ssl/tls"),

                // ── Other ──
                List.of("linux", "gnu/linux"),
                List.of("bash", "shell scripting", "shell script", "bash scripting"),
                List.of("powershell", "power shell"),
                List.of("rabbitmq", "rabbit mq"),
                List.of("nginx", "nginx server"),
                List.of("apache", "apache httpd"),
                List.of("jira", "atlassian jira"),
                List.of("confluence", "atlassian confluence")
        );

        Map<String, String> map = new HashMap<>(groups.size() * 4);
        for (List<String> group : groups) {
            String canonical = group.get(0).toLowerCase(Locale.ROOT);
            for (String alias : group) {
                map.put(alias.toLowerCase(Locale.ROOT), canonical);
            }
        }
        ALIAS_MAP = Map.copyOf(map);
    }

    /**
     * Returns the canonical form if the input is a known alias, or the
     * lower-case input itself if not found.
     */
    public static String canonicalise(String skill) {
        if (skill == null) return "";
        String key = skill.trim().toLowerCase(Locale.ROOT);
        return ALIAS_MAP.getOrDefault(key, key);
    }

    /**
     * Two skills are synonyms if they share the same canonical form.
     */
    public static boolean areSynonyms(String a, String b) {
        return canonicalise(a).equals(canonicalise(b));
    }

    /** Visible for testing. */
    static int groupCount() {
        return (int) ALIAS_MAP.values().stream().distinct().count();
    }
}
