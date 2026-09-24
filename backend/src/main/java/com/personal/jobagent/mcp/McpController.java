package com.personal.jobagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personal.jobagent.ats.AtsAdapter;
import com.personal.jobagent.ats.AtsAdapterRegistry;
import com.personal.jobagent.common.JdbcConversions;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.qa.ApplicationAnswerService;
import com.personal.jobagent.security.OwnerContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP (Model Context Protocol) Server endpoint.
 *
 * Exposes five tools following the MCP JSON-RPC 2.0 wire format:
 *   - search_jobs
 *   - get_job
 *   - generate_cover_letter
 *   - draft_answer
 *   - list_ats_adapters
 *
 * Spec: https://modelcontextprotocol.io/docs/concepts/tools
 * Endpoint: POST /mcp  (tool dispatch)
 *           GET  /mcp/tools (tool manifest)
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final JobRepository jobRepository;
    private final CoverLetterService coverLetterService;
    private final ApplicationAnswerService answerService;
    private final AtsAdapterRegistry atsAdapterRegistry;
    private final ObjectMapper objectMapper;
    private final OwnerContext ownerContext;
    @Autowired
    private JdbcTemplate db;

    public McpController(JobRepository jobRepository,
                         CoverLetterService coverLetterService,
                         ApplicationAnswerService answerService,
                         AtsAdapterRegistry atsAdapterRegistry,
                         ObjectMapper objectMapper,
                         OwnerContext ownerContext) {
        this.jobRepository = jobRepository;
        this.coverLetterService = coverLetterService;
        this.answerService = answerService;
        this.atsAdapterRegistry = atsAdapterRegistry;
        this.objectMapper = objectMapper;
        this.ownerContext = ownerContext;
    }

    /**
     * The calling account's profile.
     *
     * <p>MCP used to act as a fixed sentinel profile ({@code 00000000-...-0001})
     * and a sentinel application id. Nothing had that profile, so generated cover
     * letters and drafted answers were filed against a phantom owner — invisible
     * to the user who asked for them — while the read tools returned every
     * account's applications, emails and automation plans. Tools now act as the
     * authenticated caller, and a caller with no profile is refused rather than
     * silently writing data nobody owns.
     */
    private UUID requireProfileId() {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            throw new McpException(-32602, "No profile exists for this account");
        }
        return profileId;
    }

    /** The caller's own application for a job, if they have one; otherwise null. */
    private UUID applicationForJob(UUID profileId, UUID jobId) {
        return db.queryForList(
                        "select id from applications where job_id=? and profile_id=? order by created_at desc limit 1",
                        jobId, profileId)
                .stream().findFirst().map(row -> (UUID) row.get("id")).orElse(null);
    }

    // ── Tool Manifest ──────────────────────────────────────────────────────────

    @GetMapping("/tools")
    public ResponseEntity<?> listTools() {
        return ResponseEntity.ok(Map.of("tools", TOOL_MANIFEST));
    }

    // ── MCP Dispatch ──────────────────────────────────────────────────────────

    /**
     * MCP JSON-RPC 2.0 dispatcher.
     * Request:  { "jsonrpc": "2.0", "id": "...", "method": "tools/call",
     *             "params": { "name": "search_jobs", "arguments": { ... } } }
     * Response: { "jsonrpc": "2.0", "id": "...", "result": { "content": [...] } }
     */
    @PostMapping
    public ResponseEntity<?> dispatch(@RequestBody JsonNode body, HttpServletRequest req) {
        String requestId = body.has("id") ? body.get("id").asText() : "unknown";
        String method = body.has("method") ? body.get("method").asText() : "";

        if (!"tools/call".equals(method)) {
            return mcpError(requestId, -32601, "Method not found: " + method);
        }

        JsonNode params = body.get("params");
        if (params == null) return mcpError(requestId, -32602, "Missing params");

        String toolName = params.has("name") ? params.get("name").asText() : "";
        JsonNode arguments = params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();

        log.info("MCP tool call: {} id={}", toolName, requestId);

        try {
            Object result = switch (toolName) {
                case "search_jobs"           -> toolSearchJobs(arguments);
                case "get_job"               -> toolGetJob(arguments);
                case "generate_cover_letter" -> toolGenerateCoverLetter(arguments);
                case "draft_answer"          -> toolDraftAnswer(arguments);
                case "list_ats_adapters"     -> toolListAtsAdapters();
                case "list_applications"      -> toolListApplications();
                case "get_application"       -> toolGetApplication(arguments);
                case "get_application_status"-> toolGetApplicationStatus(arguments);
                case "get_application_timeline" -> toolGetApplicationTimeline(arguments);
                case "list_emails"            -> toolListEmails(arguments);
                case "get_automation_status" -> toolGetAutomationStatus(arguments);
                case "get_metrics"            -> toolGetMetrics();
                case "get_provider_status"    -> toolGetProviderStatus();
                default -> throw new McpException(-32601, "Unknown tool: " + toolName);
            };
            return mcpSuccess(requestId, result);
        } catch (McpException e) {
            return mcpError(requestId, e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            return mcpError(requestId, -32602, "Invalid argument: " + e.getMessage());
        } catch (Exception e) {
            log.error("MCP tool {} failed", toolName, e);
            return mcpError(requestId, -32603, "Internal error: " + e.getMessage());
        }
    }

    // ── Tool Implementations ──────────────────────────────────────────────────

    private Object toolSearchJobs(JsonNode args) {
        String q = args.has("q") ? args.get("q").asText() : null;
        String status = args.has("status") ? args.get("status").asText() : null;
        int limit = args.has("limit") ? args.get("limit").asInt(20) : 20;
        limit = Math.min(limit, 50);

        JobRepository.Page page = jobRepository.findJobs(status, q, limit, null);
        return Map.of(
                "items", page.items(),
                "count", page.items().size(),
                "next_cursor", page.nextCursor() != null ? page.nextCursor() : ""
        );
    }

    private Object toolGetJob(JsonNode args) {
        if (!args.has("job_id")) throw new IllegalArgumentException("job_id is required");
        UUID jobId = UUID.fromString(args.get("job_id").asText());
        return jobRepository.findById(jobId)
                .map(job -> (Object) Map.of("job", job))
                .orElseThrow(() -> new McpException(-32602, "Job not found: " + jobId));
    }

    private Object toolGenerateCoverLetter(JsonNode args) {
        if (!args.has("job_id")) throw new IllegalArgumentException("job_id is required");
        UUID jobId = UUID.fromString(args.get("job_id").asText());
        UUID profileId = requireProfileId();

        CoverLetterService.GenerationResult result = coverLetterService.generateCoverLetter(
                profileId, jobId, applicationForJob(profileId, jobId));

        return Map.of(
                "cover_letter_id", result.coverLetter().id().toString(),
                "body_markdown", result.coverLetter().bodyMarkdown(),
                "is_approved", result.coverLetter().isApproved(),
                "passed_validation", result.passedValidation(),
                "issues", result.issues()
        );
    }

    private Object toolDraftAnswer(JsonNode args) {
        if (!args.has("job_id")) throw new IllegalArgumentException("job_id is required");
        if (!args.has("question")) throw new IllegalArgumentException("question is required");

        UUID jobId = UUID.fromString(args.get("job_id").asText());
        String question = args.get("question").asText();
        UUID profileId = requireProfileId();

        ApplicationAnswerService.AnswerResult result = answerService.draftAnswer(
                profileId, jobId, applicationForJob(profileId, jobId), question);

        return Map.of(
                "answer_id", result.record().id(),
                "answer_text", result.record().answerText(),
                "status", result.record().status(),
                "outcome", result.outcome(),
                "needs_user_input", "NEEDS_USER_INPUT".equals(result.record().status())
        );
    }

    // Every tool below is scoped to the authenticated caller's profile. The
    // owner is never a tool argument — an MCP client cannot name whose data it
    // wants, so there is no argument to tamper with.
    private Object toolListApplications() {
        return Map.of("items", db.queryForList("select id,job_id,status,mode,created_at,updated_at from applications where profile_id=? order by created_at desc limit 100", requireProfileId()));
    }
    private Object toolGetApplication(JsonNode args) {
        UUID id=requiredUuid(args,"application_id");
        return db.queryForList("select id,job_id,status,mode,created_at,updated_at from applications where id=? and profile_id=?",id,requireProfileId()).stream().findFirst().map(x->Map.of("application",x)).orElseThrow(()->new McpException(-32602,"Application not found: "+id));
    }
    private Object toolGetApplicationStatus(JsonNode args) { UUID id=requiredUuid(args,"application_id"); return db.queryForList("select id,status,updated_at from applications where id=? and profile_id=?",id,requireProfileId()).stream().findFirst().orElseThrow(()->new McpException(-32602,"Application not found: "+id)); }
    // payload is jsonb: queryForList handed the driver's PGobject to Jackson, which rendered it
    // as {"type":"jsonb","value":"..."} instead of the event payload itself.
    // The join back to applications is what makes application_events (which has no owner
    // column of its own) inheritable-scoped rather than unscoped.
    private Object toolGetApplicationTimeline(JsonNode args) { UUID id=requiredUuid(args,"application_id"); return Map.of("items",db.query("select ev.id,ev.type,ev.payload::text as payload,ev.actor,ev.occurred_at from application_events ev join applications a on a.id=ev.application_id where ev.application_id=? and a.profile_id=? order by ev.occurred_at,ev.id",(rs,n)->{ Map<String,Object> row=new LinkedHashMap<>(); row.put("id",rs.getObject("id")); row.put("type",rs.getString("type")); row.put("payload",JdbcConversions.readJson(rs,"payload",objectMapper)); row.put("actor",rs.getString("actor")); row.put("occurred_at",rs.getObject("occurred_at")); return row; },id,requireProfileId())); }
    private Object toolListEmails(JsonNode args) { UUID pid=requireProfileId(); if(args.has("application_id")){UUID id=requiredUuid(args,"application_id");return Map.of("items",db.queryForList("select id,message_id,from_address,subject,received_at,application_id,classification,classification_confidence from emails where application_id=? and profile_id=? order by received_at desc limit 100",id,pid));} return Map.of("items",db.queryForList("select id,message_id,from_address,subject,received_at,application_id,classification,classification_confidence from emails where profile_id=? order by received_at desc limit 100",pid)); }
    private Object toolGetAutomationStatus(JsonNode args) { UUID pid=requireProfileId(); if(args.has("plan_id")){UUID id=requiredUuid(args,"plan_id");return db.queryForList("select id,application_id,status,submit_approved,heartbeat_at,updated_at from automation_plans where id=? and profile_id=?",id,pid).stream().findFirst().orElseThrow(()->new McpException(-32602,"Automation plan not found: "+id));} return Map.of("items",db.queryForList("select id,application_id,status,submit_approved,heartbeat_at,updated_at from automation_plans where profile_id=? order by updated_at desc limit 100",pid)); }
    private Object toolGetMetrics() { UUID pid=requireProfileId(); return Map.of("applications",db.queryForObject("select count(*) from applications where profile_id=?",Long.class,pid),"emails",db.queryForObject("select count(*) from emails where profile_id=?",Long.class,pid),"automation_plans",db.queryForObject("select count(*) from automation_plans where profile_id=?",Long.class,pid),"worker_events",db.queryForObject("select count(*) from worker_events where profile_id=?",Long.class,pid),"notifications",db.queryForObject("select count(*) from notifications where profile_id=?",Long.class,pid)); }
    private Object toolGetProviderStatus() { return Map.of("providers",db.queryForList("select id,kind,enabled,base_url from llm_providers order by id")); }
    private UUID requiredUuid(JsonNode args,String name){if(!args.has(name))throw new IllegalArgumentException(name+" is required");return UUID.fromString(args.get(name).asText());}

    private Object toolListAtsAdapters() {
        List<AtsAdapter> adapters = atsAdapterRegistry.getAdapters();
        List<Map<String, Object>> adapterList = adapters.stream()
                .map(adapter -> Map.<String, Object>of(
                        "kind", adapter.kind().name()
                ))
                .toList();
        return Map.of("adapters", adapterList, "count", adapterList.size());
    }

    // ── MCP Wire Format Helpers ───────────────────────────────────────────────

    private ResponseEntity<?> mcpSuccess(String id, Object result) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode contentArr = content.putArray("content");
        ObjectNode textNode = contentArr.addObject();
        textNode.put("type", "text");
        try {
            textNode.put("text", objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            textNode.put("text", result.toString());
        }

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.set("result", content);
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<?> mcpError(String id, int code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.set("error", error);
        return ResponseEntity.badRequest().body(resp);
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    static class McpException extends RuntimeException {
        final int code;
        McpException(int code, String message) { super(message); this.code = code; }
    }

    // ── Tool Manifest (static schema, no DB) ──────────────────────────────────

    private static final List<Map<String, Object>> TOOL_MANIFEST = List.of(
            Map.of(
                    "name", "search_jobs",
                    "description", "Search discovered jobs by keyword and/or status filter",
                    "inputSchema", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "q", Map.of("type", "string", "description", "Keyword search across title, description"),
                                    "status", Map.of("type", "string", "description", "Filter by status: DISCOVERED|ANALYSED|SCORED|DECIDED"),
                                    "limit", Map.of("type", "integer", "description", "Max results (1-50)", "default", 20)
                            )
                    )
            ),
            Map.of(
                    "name", "get_job",
                    "description", "Fetch full details for a specific job by its UUID",
                    "inputSchema", Map.of(
                            "type", "object",
                            "required", List.of("job_id"),
                            "properties", Map.of(
                                    "job_id", Map.of("type", "string", "format", "uuid", "description", "UUID of the job")
                            )
                    )
            ),
            Map.of(
                    "name", "generate_cover_letter",
                    "description", "Generate an ATS-optimised cover letter for a job using the candidate profile",
                    "inputSchema", Map.of(
                            "type", "object",
                            "required", List.of("job_id"),
                            "properties", Map.of(
                                    "job_id", Map.of("type", "string", "format", "uuid")
                            )
                    )
            ),
            Map.of(
                    "name", "draft_answer",
                    "description", "Draft an answer to an application form question using the candidate profile",
                    "inputSchema", Map.of(
                            "type", "object",
                            "required", List.of("job_id", "question"),
                            "properties", Map.of(
                                    "job_id", Map.of("type", "string", "format", "uuid"),
                                    "question", Map.of("type", "string", "description", "The application form question text")
                            )
                    )
            ),
            Map.of(
                    "name", "list_ats_adapters",
                    "description", "List all supported ATS systems (Greenhouse, Lever, Ashby, Workday, etc.)",
                    "inputSchema", Map.of("type", "object", "properties", Map.of())
            ),
            Map.of("name","list_applications","description","List application records and statuses","inputSchema",Map.of("type","object","properties",Map.of())),
            Map.of("name","get_application","description","Get one application","inputSchema",Map.of("type","object","required",List.of("application_id"))),
            Map.of("name","get_application_status","description","Get current application status","inputSchema",Map.of("type","object","required",List.of("application_id"))),
            Map.of("name","get_application_timeline","description","Get application timeline","inputSchema",Map.of("type","object","required",List.of("application_id"))),
            Map.of("name","list_emails","description","List correlated mailbox messages without message bodies","inputSchema",Map.of("type","object","properties",Map.of("application_id",Map.of("type","string")))),
            Map.of("name","get_automation_status","description","Get automation plan status","inputSchema",Map.of("type","object","properties",Map.of("plan_id",Map.of("type","string")))),
            Map.of("name","get_metrics","description","Get local pipeline metrics","inputSchema",Map.of("type","object","properties",Map.of())),
            Map.of("name","get_provider_status","description","Get configured LLM provider status without secrets","inputSchema",Map.of("type","object","properties",Map.of()))
    );
}