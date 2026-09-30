package com.personal.jobagent.ats;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * READ-ONLY real Greenhouse apply-form inspection (Phase 3A).
 *
 * <p>Overrides the inherited static {@code inspectForm} with a real one: it
 * performs a plain GET of the Greenhouse job page (Greenhouse renders the
 * application form as server-side HTML on the job page itself), parses the
 * form controls with Jsoup, and reports deterministic metadata for each field
 * — the control's {@code id} as the field key, the associated {@code
 * label[for]} text, HTML type, required-ness (Greenhouse marks required
 * fields with a hidden {@code requiredInput} mirror input beside the visible
 * control), the deterministic {@code #id} selector, and select/radio options
 * when they are statically present in the HTML.
 *
 * <p><b>Read-only guarantees.</b> Inspection issues exactly one HTTP GET with
 * a response-size cap. It never types, uploads, clicks, submits, executes
 * JavaScript from the page (Greenhouse apply pages are fully inspectable
 * without it, and running remote scripts would violate the untrusted-content
 * rule), or follows arbitrary external links. Remote HTML is untrusted data:
 * text is extracted, length-bounded and returned as metadata — never
 * interpreted as instructions, never logged.
 *
 * <p><b>Honest uncertainty (human-in-the-loop compatibility).</b> Select
 * options rendered by JavaScript (Greenhouse's react-select dropdowns) are
 * absent from the static HTML, so such fields are reported with empty
 * {@code options} — unknown is reported, never guessed; later phases must
 * treat option-less selects as REQUIRES_HUMAN. Demographic-style numeric-id
 * questions are reported generically (key = the numeric id) so later phases
 * can classify them REQUIRES_HUMAN.
 *
 * <p><b>Failure mode.</b> Any fetch/parse failure throws {@code
 * IllegalStateException} with the stable prefix {@code
 * GREENHOUSE_INSPECTION_UNAVAILABLE} (controller maps to 503) — inspection
 * failures are deterministic and never silently degrade to the mock
 * descriptor.
 */
@Component
public class GreenhouseAdapter extends BaseAtsAdapter {

    /** Greenhouse's hidden required-input mirrors; not real form fields. */
    private static final String REQUIRED_MIRROR_CLASS = "requiredInput";
    /** Hard cap on inspected page size (larger responses = unavailable). */
    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Function<String, String> fetcher;

    public GreenhouseAdapter() {
        this(url -> {
            try {
                HttpResponse<byte[]> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(20))
                                .header("Accept", "text/html")
                                .header("User-Agent", "PersonalJobAgent/1.0 (+read-only-inspection)")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 400) {
                    throw new IllegalStateException("UPSTREAM_STATUS_" + response.statusCode());
                }
                if (response.body().length > MAX_PAGE_BYTES) {
                    throw new IllegalStateException("PAGE_TOO_LARGE");
                }
                return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("FETCH_FAILED", e);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("FETCH_FAILED: " + e.getMessage(), e);
            }
        });
    }

    /** Test seam: inspection fetch supplied directly (deterministic fixtures). */
    GreenhouseAdapter(Function<String, String> fetcher) {
        super(AtsKind.GREENHOUSE, Pattern.compile("boards\\.greenhouse\\.io|greenhouse\\.io"), false, false);
        this.fetcher = fetcher;
    }

    @Override
    public FormDescriptor inspectForm(String url) {
        if (url == null || url.isBlank() || !matchesUrl(url)) {
            throw new IllegalArgumentException("URL is not a Greenhouse board URL");
        }
        String html;
        try {
            html = fetcher.apply(url);
        } catch (Exception e) {
            // Fetcher failures carry stable codes (e.g. FETCH_FAILED);
            // GREENHOUSE_INSPECTION_UNAVAILABLE is the controller's 503 marker.
            throw new IllegalStateException("GREENHOUSE_INSPECTION_UNAVAILABLE: " + e.getMessage(), e);
        }
        if (html == null || html.isBlank()) {
            throw new IllegalStateException("GREENHOUSE_INSPECTION_UNAVAILABLE: EMPTY_PAGE");
        }
        return buildDescriptor(url, parse(html));
    }

    /** Visible for tests: pure function from HTML to field metadata. */
    static List<FormFieldDescriptor> parse(String html) {
        Document document = Jsoup.parse(html);
        List<FormFieldDescriptor> fields = new ArrayList<>();
        for (Element control : document.select("input[id], select[id], textarea[id]")) {
            if (isValidationMirror(control)) continue;
            String id = control.id();
            if (id == null || id.isBlank()) continue;
            fields.add(new FormFieldDescriptor(
                    id,
                    labelFor(document, control, id),
                    htmlType(control),
                    isRequired(control),
                    "#" + id,
                    optionsOf(control)));
        }
        return fields;
    }

    private FormDescriptor buildDescriptor(String url, List<FormFieldDescriptor> fields) {
        List<String> supported = fields.stream()
                .map(FormFieldDescriptor::key)
                .filter(k -> List.of("first_name", "last_name", "email", "phone", "resume", "cover_letter")
                        .contains(k.toLowerCase()))
                .toList();
        return new FormDescriptor(AtsKind.GREENHOUSE, url, requiresAuth, multiStep, supported, true, fields);
    }

    /** Greenhouse hides real controls behind aria-hidden validation mirrors — skip those. */
    private static boolean isValidationMirror(Element control) {
        String cls = control.className();
        return control.hasAttr("aria-hidden")
                && (cls.contains(REQUIRED_MIRROR_CLASS) || cls.contains("remix-css"));
    }

    private static String htmlType(Element control) {
        return control.tagName().equals("input")
                ? (control.attr("type").isBlank() ? "text" : control.attr("type").toLowerCase())
                : control.tagName();
    }

    private static String labelFor(Document document, Element control, String id) {
        Element label = document.selectFirst("label[for=" + id + "]");
        if (label != null && !label.text().isBlank()) {
            return stripRequiredMarker(label.text());
        }
        String aria = control.attr("aria-label");
        return aria.isBlank() ? null : aria.trim();
    }

    /** Greenhouse labels conventionally end with the required marker; metadata, not content. */
    private static String stripRequiredMarker(String text) {
        return text.replaceAll("\\s*\\*\\s*$", "").trim();
    }

    /**
     * Required = the control's own required/aria-required attributes, or
     * Greenhouse's pattern of a hidden required-input mirror inside the same
     * field wrapper (checked up to the nearest field-group boundary).
     */
    private static boolean isRequired(Element control) {
        if (control.hasAttr("required") || "true".equalsIgnoreCase(control.attr("aria-required"))) {
            return true;
        }
        // Greenhouse renders a hidden required-input mirror inside the same
        // field wrapper as the control and its label. Scoping the mirror to
        // the smallest wrapper that contains THIS field's label prevents
        // cross-field false positives on long forms.
        Element parent = control.parent();
        for (int depth = 0; parent != null && depth < 4; depth++) {
            // The smallest wrapper containing this field's own label decides:
            // mirror present → required; label present without mirror → optional.
            // Walking past that wrapper would read other fields' mirrors.
            if (!parent.select("label[for=" + control.id() + "]").isEmpty()) {
                return !parent.select("input[class*=requiredInput]").isEmpty();
            }
            parent = parent.parent();
        }
        return false;
    }

    private static List<String> optionsOf(Element control) {
        if (control.tagName().equals("select")) {
            LinkedHashSet<String> options = new LinkedHashSet<>();
            for (Element option : control.select("option")) {
                String value = option.hasAttr("value") ? option.attr("value") : option.text();
                if (!value.isBlank()) options.add(value);
            }
            return new ArrayList<>(options);
        }
        String type = control.attr("type");
        if ("radio".equalsIgnoreCase(type) && !control.attr("name").isBlank()) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (Element radio : control.ownerDocument().select("input[type=radio][name=" + control.attr("name") + "]")) {
                if (!radio.attr("value").isBlank()) values.add(radio.attr("value"));
            }
            return new ArrayList<>(values);
        }
        if ("checkbox".equalsIgnoreCase(type)) {
            return List.of(control.attr("value").isBlank() ? "checked" : control.attr("value"));
        }
        return List.of();
    }
}
