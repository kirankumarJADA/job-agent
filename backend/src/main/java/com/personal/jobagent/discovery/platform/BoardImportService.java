package com.personal.jobagent.discovery.platform;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.platform.adapters.DetailFetchAdapters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code POST /jobs/import-url}: turn a pasted careers/job URL into a
 * configured board source, safely.
 *
 * <ol>
 *   <li><b>Validate</b> — absolute http(s) URL, ≤ 2048 chars, no credentials,
 *       default port, not an IP literal or internal name, and its host must
 *       resolve only to public addresses ({@link AddressGuard}). http is
 *       upgraded to https.</li>
 *   <li><b>Detect</b> — match the URL against every platform's URL patterns. If
 *       nothing matches, fetch the page ONCE through {@link SafeHttpClient}
 *       (SSRF-checked on every redirect hop, 2 MB cap, HTML only, 10 s
 *       timeout) and look for an embedded board link or a platform signature.
 *       No other page is crawled.</li>
 *   <li><b>Explain</b> — a recognised but unsupported platform is reported with
 *       its status and reason; a supported platform whose board cannot be
 *       derived asks for the board identifier (with the expected format).</li>
 *   <li><b>Preview</b> (optional) — one listing page of the resolved board,
 *       so the candidate can inspect what would be followed.</li>
 *   <li><b>Configure</b> (only with {@code confirm=true}) — create the source
 *       (DISCOVERY_ONLY policy, every-6-hours schedule) and run it once.
 *       An existing disabled source is never re-enabled.</li>
 * </ol>
 * At most two imports run concurrently; the endpoint also shares the
 * per-account "discovery" rate-limit bucket.
 */
@Service
public class BoardImportService {

    public record ImportRequest(String url, String platform, String board, Boolean preview, Boolean confirm,
                                String employerName) {
    }

    public static class ImportRejected extends RuntimeException {
        private final int status;

        public ImportRejected(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static final Pattern EMBEDDED_URL = Pattern.compile("https?://[A-Za-z0-9.-]+(?::\\d+)?/[^\\s\"'<>)]*");

    private final JdbcTemplate db;
    private final PlatformRegistry registry;
    private final BoardDiscoveryService runner;
    private final AddressGuard guard;
    private final Supplier<SafeHttpClient> pageClient;
    private final Semaphore permits = new Semaphore(2);

    @Autowired
    public BoardImportService(JdbcTemplate db, PlatformRegistry registry, BoardDiscoveryService runner) {
        this(db, registry, runner, new AddressGuard(), () -> SafeHttpClient.live(SafeHttpClient.Settings.defaults()
                .withMaxRequests(6).withMaxBytes(2 * 1024 * 1024).withTimeout(Duration.ofSeconds(10))));
    }

    public BoardImportService(JdbcTemplate db, PlatformRegistry registry, BoardDiscoveryService runner,
                              AddressGuard guard, Supplier<SafeHttpClient> pageClient) {
        this.db = db;
        this.registry = registry;
        this.runner = runner;
        this.guard = guard;
        this.pageClient = pageClient;
    }

    public Map<String, Object> importUrl(ImportRequest request) {
        if (!permits.tryAcquire()) throw new ImportRejected(429, "Another import is in progress; try again shortly");
        try {
            return doImport(request);
        } finally {
            permits.release();
        }
    }

    private Map<String, Object> doImport(ImportRequest request) {
        URI url = validate(request.url());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url.toString());

        Optional<PlatformRegistry.Recognition> recognition;
        String recognitionError = null;
        if (request.platform() != null && !request.platform().isBlank()) {
            PlatformKind kind = PlatformKind.lookup(request.platform())
                    .orElseThrow(() -> new ImportRejected(400, "Unknown platform: " + request.platform()));
            recognition = Optional.of(new PlatformRegistry.Recognition(kind, null));
        } else {
            try {
                recognition = registry.recognize(url);
            } catch (BoardConfigException e) {
                recognition = Optional.empty();
                recognitionError = e.getMessage();
            }
            if (recognition.isEmpty() && recognitionError == null) recognition = sniff(url);
        }

        if (recognition.isEmpty()) {
            out.put("status", "UNRECOGNIZED");
            out.put("message", recognitionError != null ? recognitionError
                    : "No supported job board was found at this URL. Choose the platform and enter its board identifier.");
            out.put("platforms", platformHints());
            return out;
        }
        PlatformKind kind = recognition.get().kind();
        out.put("platform", platform(kind));
        Optional<BoardAdapter> adapter = registry.adapter(kind);
        if (adapter.isEmpty()) {
            out.put("status", "UNSUPPORTED");
            out.put("message", kind.displayName() + " is " + kind.readiness().name().toLowerCase(Locale.ROOT)
                    + " in this version, so it cannot be followed yet. (" + kind.configHint() + ")");
            return out;
        }

        BoardAdapter.BoardRef board = recognition.get().board();
        if (request.board() != null && !request.board().isBlank()) {
            try {
                board = adapter.get().parseIdentifier(request.board());
            } catch (BoardConfigException e) {
                throw new ImportRejected(400, e.getMessage());
            }
        }
        if (board == null) {
            out.put("status", "NEEDS_CONFIGURATION");
            out.put("message", "This is a " + kind.displayName() + " page, but its board could not be determined from the URL. "
                    + "Enter the board identifier: " + kind.configHint());
            return out;
        }
        out.put("board", board.identifier());
        out.put("status", "RESOLVED");
        existingSource(kind, board.identifier()).ifPresent(existing -> out.put("existing_source", existing));

        if (Boolean.TRUE.equals(request.preview())) {
            out.put("preview", preview(adapter.get(), board, request.employerName()));
        }
        if (Boolean.TRUE.equals(request.confirm())) {
            configure(kind, board, request.employerName(), out);
        }
        return out;
    }

    /** @throws ImportRejected 400 with a reason the candidate can act on */
    URI validate(String raw) {
        if (raw == null || raw.isBlank()) throw new ImportRejected(400, "url is required");
        String value = raw.strip();
        if (value.length() > 2048) throw new ImportRejected(400, "url is too long");
        URI uri;
        try {
            uri = new URI(value);
        } catch (java.net.URISyntaxException e) {
            throw new ImportRejected(400, "url must be well-formed");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) throw new ImportRejected(400, "only http(s) URLs can be imported");
        if (uri.getHost() == null) throw new ImportRejected(400, "url must be well-formed");
        if (uri.getRawUserInfo() != null) throw new ImportRejected(400, "URLs with credentials are not accepted");
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) throw new ImportRejected(400, "non-standard ports are not accepted");
        URI https;
        try {
            https = new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), -1, uri.getPath(), uri.getQuery(), null);
        } catch (java.net.URISyntaxException e) {
            throw new ImportRejected(400, "url must be well-formed");
        }
        try {
            guard.check(https);
        } catch (AddressGuard.UnsafeAddressException e) {
            throw new ImportRejected(400, "This URL cannot be fetched: " + e.getMessage());
        }
        return https;
    }

    /** One guarded page fetch looking for an embedded board or a platform signature. */
    private Optional<PlatformRegistry.Recognition> sniff(URI url) {
        String html;
        try {
            html = pageClient.get().get(url, SafeHttpClient.Accept.HTML);
        } catch (SafeHttpClient.FetchException e) {
            if (e.category() == FailureCategory.UNSAFE_ADDRESS) throw new ImportRejected(400, "This URL cannot be fetched: " + e.getMessage());
            return Optional.empty();
        }
        Set<String> candidates = new LinkedHashSet<>();
        Matcher m = EMBEDDED_URL.matcher(html.replace("&amp;", "&"));
        while (m.find() && candidates.size() < 500) candidates.add(m.group());
        for (String candidate : candidates) {
            try {
                Optional<PlatformRegistry.Recognition> r = registry.recognize(URI.create(candidate));
                if (r.isPresent() && r.get().fetchable() && r.get().kind() != PlatformKind.TSENTA) return r;
            } catch (IllegalArgumentException ignored) {
                // not a usable URL
            }
        }
        String lower = html.toLowerCase(Locale.ROOT);
        if (lower.contains("phenompeople") || lower.contains("phapp.ddo")) {
            DetailFetchAdapters.Phenom phenom = (DetailFetchAdapters.Phenom) registry.adapter(PlatformKind.PHENOM).orElseThrow();
            return Optional.of(new PlatformRegistry.Recognition(PlatformKind.PHENOM,
                    phenom.fromPath(url.getHost(), url).orElse(null)));
        }
        return Optional.empty();
    }

    private Map<String, Object> preview(BoardAdapter adapter, BoardAdapter.BoardRef board, String employer) {
        SafeHttpClient http = pageClient.get();
        BoardAdapter.ListingResult listing;
        try {
            listing = adapter.list(board, new BoardAdapter.CrawlContext(http, 1, Instant.now(), employer));
        } catch (RuntimeException e) {
            listing = BoardAdapter.ListingResult.failed(FailureCategory.MALFORMED_RESPONSE, e.getClass().getSimpleName());
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("listed_on_first_page", listing.entries().size());
        p.put("more_pages_possible", !listing.complete());
        List<String> titles = new ArrayList<>();
        for (BoardAdapter.ListingEntry e : listing.entries()) {
            String title = e.job() != null ? e.job().title() : e.title();
            if (title != null) titles.add(title);
            if (titles.size() == 5) break;
        }
        p.put("sample_titles", titles);
        if (listing.employerName() != null) p.put("employer", listing.employerName());
        if (listing.failure() != null && listing.failure() != FailureCategory.BUDGET_EXHAUSTED) {
            p.put("problem", listing.failure().name());
        }
        return p;
    }

    private void configure(PlatformKind kind, BoardAdapter.BoardRef board, String employerName, Map<String, Object> out) {
        Optional<Map<String, Object>> existing = existingSource(kind, board.identifier());
        java.util.UUID id;
        if (existing.isPresent()) {
            id = (java.util.UUID) existing.get().get("id");
            if (!Boolean.TRUE.equals(existing.get().get("enabled"))) {
                out.put("status", "SOURCE_DISABLED");
                out.put("message", "This board is already configured but disabled; it was left disabled.");
                return;
            }
        } else {
            id = UuidV7.generate();
            String display = (employerName != null && !employerName.isBlank() ? employerName.strip() : board.identifier())
                    + " (" + kind.displayName() + ")";
            db.update("""
                    insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy,
                                             rate_limit_per_min, schedule_cron, enabled, health)
                    values (?, ?, ?, ?, '{"discovery": true, "api": true, "automation": false}'::jsonb, 'DISCOVERY_ONLY',
                            30, ?, true, '{}'::jsonb)
                    on conflict (kind, org_identifier) do nothing
                    """, id, kind.name(), board.identifier(), display.length() > 200 ? display.substring(0, 200) : display,
                    kind == PlatformKind.TSENTA ? "0 30 */12 * * ?" : "0 0 */6 * * ?");
            id = existingSource(kind, board.identifier()).map(r -> (java.util.UUID) r.get("id")).orElse(id);
        }
        BoardDiscoveryService.SourceRunReport report = runner.run(id, BoardDiscoveryService.Mode.IMPORT);
        out.put("status", "CONFIGURED");
        out.put("source_id", id.toString());
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("status", report.status());
        run.put("complete", report.complete());
        run.put("counts", report.counts());
        if (report.failureCategory() != null) run.put("failure", report.failureCategory());
        out.put("run", run);
    }

    private Optional<Map<String, Object>> existingSource(PlatformKind kind, String identifier) {
        List<Map<String, Object>> rows = db.queryForList(
                "select id, enabled, display_name from job_sources where kind = ? and org_identifier = ?", kind.name(), identifier);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static Map<String, Object> platform(PlatformKind kind) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", kind.name());
        p.put("name", kind.displayName());
        p.put("readiness", kind.readiness().name());
        p.put("board_format", kind.configHint());
        return p;
    }

    private List<Map<String, Object>> platformHints() {
        List<Map<String, Object>> hints = new ArrayList<>();
        for (PlatformKind kind : registry.supportedKinds()) hints.add(platform(kind));
        return hints;
    }
}
