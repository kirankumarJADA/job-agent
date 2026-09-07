package com.personal.jobagent.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.llm.TaskType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads datasets/benchmarks/<suite>/<version>.jsonl. The dataset root is
 * configurable (app.benchmarks.dataset-root) because the path differs
 * between "running via docker compose" (mounted at /app/datasets per
 * infra/docker-compose.yml) and "running mvn spring-boot:run directly from
 * backend/" (../datasets relative to the working directory).
 */
@Component
public class SuiteLoader {

    private final ObjectMapper objectMapper;

    @Value("${app.benchmarks.dataset-root:../datasets}")
    private String datasetRoot;

    public SuiteLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @param suiteRef e.g. "job_classification@v1" */
    public List<BenchmarkCase> load(String suiteRef) throws IOException {
        String[] parts = suiteRef.split("@");
        if (parts.length != 2) {
            throw new IllegalArgumentException("suite must be in '<name>@<version>' form, got: " + suiteRef);
        }
        Path file = Path.of(datasetRoot, "benchmarks", parts[0], parts[1] + ".jsonl");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Suite file not found: " + file
                    + " (checked dataset root: " + datasetRoot + ")");
        }

        List<BenchmarkCase> cases = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> raw = objectMapper.readValue(line, Map.class);
            cases.add(new BenchmarkCase(
                    (String) raw.get("case_id"),
                    TaskType.valueOf((String) raw.get("task_type")),
                    (String) raw.get("input"),
                    (String) raw.get("expected"),
                    (String) raw.get("grader")
            ));
        }
        return cases;
    }
}
