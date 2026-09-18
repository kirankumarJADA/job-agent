package com.personal.jobagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personal.jobagent.ats.AtsAdapter;
import com.personal.jobagent.ats.AtsAdapterRegistry;
import com.personal.jobagent.ats.AtsKind;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.qa.ApplicationAnswerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class McpControllerTest {

    private McpController controller;
    private JobRepository jobRepository;
    private CoverLetterService coverLetterService;
    private ApplicationAnswerService answerService;
    private AtsAdapterRegistry atsAdapterRegistry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        coverLetterService = Mockito.mock(CoverLetterService.class);
        answerService = Mockito.mock(ApplicationAnswerService.class);
        atsAdapterRegistry = Mockito.mock(AtsAdapterRegistry.class);
        objectMapper = new ObjectMapper();
        controller = new McpController(jobRepository, coverLetterService, answerService,
                atsAdapterRegistry, objectMapper);
    }

    @Test
    void listToolsReturnsManifest() {
        ResponseEntity<?> response = controller.listTools();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("tools");
        @SuppressWarnings("unchecked")
        List<?> tools = (List<?>) body.get("tools");
        assertThat(tools).hasSize(13);
    }

    @Test
    void unknownMethodReturnsError() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", "req-1");
        body.put("method", "tools/list"); // valid MCP but not tools/call

        ResponseEntity<?> response = controller.dispatch(body, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String json = response.getBody().toString();
        assertThat(json).contains("Method not found");
    }

    @Test
    void unknownToolNameReturnsError() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", "req-2");
        body.put("method", "tools/call");
        ObjectNode params = body.putObject("params");
        params.put("name", "nonexistent_tool");
        params.putObject("arguments");

        ResponseEntity<?> response = controller.dispatch(body, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String json = response.getBody().toString();
        assertThat(json).contains("Unknown tool");
    }

    @Test
    void searchJobsToolReturnsResults() {
        // Arrange: mock repo to return a simple page
        JobRepository.Page mockPage = new JobRepository.Page(List.of(), null);
        when(jobRepository.findJobs(any(), any(), any(Integer.class), any())).thenReturn(mockPage);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", "req-3");
        body.put("method", "tools/call");
        ObjectNode params = body.putObject("params");
        params.put("name", "search_jobs");
        ObjectNode args = params.putObject("arguments");
        args.put("q", "backend");
        args.put("limit", 10);

        ResponseEntity<?> response = controller.dispatch(body, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String json = response.getBody().toString();
        assertThat(json).contains("content");
    }

    @Test
    void listAtsAdaptersToolReturnsList() {
        AtsAdapter mockAdapter = Mockito.mock(AtsAdapter.class);
        when(mockAdapter.kind()).thenReturn(AtsKind.GREENHOUSE);
        when(atsAdapterRegistry.getAdapters()).thenReturn(List.of(mockAdapter));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", "req-4");
        body.put("method", "tools/call");
        ObjectNode params = body.putObject("params");
        params.put("name", "list_ats_adapters");
        params.putObject("arguments");

        ResponseEntity<?> response = controller.dispatch(body, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String json = response.getBody().toString();
        assertThat(json).contains("GREENHOUSE");
    }
}