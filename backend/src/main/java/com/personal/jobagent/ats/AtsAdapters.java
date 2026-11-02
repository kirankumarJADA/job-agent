package com.personal.jobagent.ats;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class AtsAdapters {

    // Greenhouse (real read-only inspection) and Ashby (honest detection
    // boundary — its apply forms are client-rendered) are top-level real
    // adapters; see GreenhouseAdapter and AshbyAdapter. Lever, below, is the
    // third: also real, read-only, static-HTML inspection (Phase 6 — see
    // automation/LeverExecutionPlanService's module comment for why its form
    // supports this the same way Greenhouse's does, unlike Zoho Recruit's
    // client-rendered one).

    /**
     * Real, read-only Lever apply-form inspection (Phase 6).
     *
     * <p>Verified directly against a real, live Lever posting
     * (`jobs.lever.co/palantir/{posting}/apply`, the exact org the discovery-side
     * {@code LeverAdapter} already verifies live postings against): a plain GET
     * returns the entire application form as server-rendered HTML — a single
     * {@code <form id="application-form">} with real {@code name}-addressed
     * inputs for every standard field ({@code name}, {@code email},
     * {@code phone}, {@code location}, {@code org}, {@code urls[LinkedIn]},
     * {@code urls[GitHub]}, {@code urls[Portfolio]}, the {@code resume} file
     * input, and a free-text {@code comments} field used for a cover
     * letter/anything else) plus zero or more per-posting custom questions.
     *
     * <p>Required-ness for the standard fields is read directly from the HTML
     * {@code required} attribute Lever's own template renders — simpler and
     * more reliable than Greenhouse's required-mirror heuristic, since Lever
     * marks required-ness explicitly rather than through a hidden sibling
     * input. Custom per-posting questions are embedded as a structured JSON
     * blob (type, text, required, and — for choice questions — every option's
     * text and stable {@code optionId}) inside one hidden input's
     * {@code value} per question "card"; this is parsed directly rather than
     * inferred from rendered markup, so required-ness and option sets for
     * custom questions are exact, not guessed. A posting's real submission is
     * gated behind an hCaptcha widget tied to the form's submit button — this
     * adapter never submits anything, so that is not a concern for inspection,
     * but it is the structural reason {@link LeverAdapter the execution plan
     * service} never emits a submit step for this platform.
     */
    @Component
    public static class LeverAdapter extends BaseAtsAdapter {

        private static final int MAX_PAGE_BYTES = 4 * 1024 * 1024;
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        /** The standard fields every Lever apply form renders, by their stable {@code name} attribute. */
        static final List<String> STANDARD_FIELDS = List.of(
                "name", "email", "phone", "location", "org",
                "urls[LinkedIn]", "urls[GitHub]", "urls[Portfolio]", "urls[Twitter]",
                "resume", "comments");

        private final Function<String, String> fetcher;

        public LeverAdapter() {
            this(url -> {
                try {
                    HttpResponse<byte[]> response = CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                                    .timeout(Duration.ofSeconds(20))
                                    .header("Accept", "text/html")
                                    .header("User-Agent", "PersonalJobAgent/1.0 (+read-only-inspection)")
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() >= 300 && response.statusCode() < 400) {
                        throw new IllegalStateException("REDIRECT_NOT_FOLLOWED");
                    }
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
        public LeverAdapter(Function<String, String> fetcher) {
            super(AtsKind.LEVER, Pattern.compile("jobs\\.lever\\.co|jobs\\.eu\\.lever\\.co"), false, false);
            this.fetcher = fetcher;
        }

        @Override
        public boolean matchesUrl(String url) {
            if (url == null || url.isBlank()) return false;
            try {
                URI uri = URI.create(url);
                String host = uri.getHost();
                return "https".equalsIgnoreCase(uri.getScheme())
                        && (uri.getPort() == -1 || uri.getPort() == 443)
                        && uri.getUserInfo() == null
                        && ("jobs.lever.co".equalsIgnoreCase(host) || "jobs.eu.lever.co".equalsIgnoreCase(host));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        @Override
        public boolean supportsLiveInspection() {
            return true;
        }

        @Override
        public FormDescriptor inspectForm(String url) {
            if (url == null || url.isBlank() || !matchesUrl(url)) {
                throw new IllegalArgumentException("URL is not a Lever job-posting URL");
            }
            String html;
            try {
                html = fetcher.apply(url);
            } catch (Exception e) {
                throw new IllegalStateException("LEVER_INSPECTION_UNAVAILABLE: " + e.getMessage(), e);
            }
            if (html == null || html.isBlank()) {
                throw new IllegalStateException("LEVER_INSPECTION_UNAVAILABLE: EMPTY_PAGE");
            }
            List<AtsAdapter.FormFieldDescriptor> fields = LeverFormParser.parse(html);
            List<String> supported = fields.stream()
                    .map(AtsAdapter.FormFieldDescriptor::key)
                    .filter(STANDARD_FIELDS::contains)
                    .toList();
            return new FormDescriptor(AtsKind.LEVER, url, false, false, supported, true, fields);
        }
    }

    /**
     * Pure HTML → field-metadata parsing for a Lever apply form, split out of
     * {@link LeverAdapter} so it is trivially unit-testable against a saved
     * fixture (mirrors {@code GreenhouseAdapter.parse}'s own package-visible
     * static method).
     */
    static final class LeverFormParser {
        private LeverFormParser() {}

        static List<AtsAdapter.FormFieldDescriptor> parse(String html) {
            Document document = Jsoup.parse(html);
            List<AtsAdapter.FormFieldDescriptor> fields = new ArrayList<>();
            for (String name : LeverAdapter.STANDARD_FIELDS) {
                Element control = document.selectFirst(
                        "[name=\"" + name.replace("\"", "") + "\"]:not([type=hidden])");
                if (control == null) continue;
                fields.add(new AtsAdapter.FormFieldDescriptor(
                        name,
                        labelFor(control, name),
                        htmlType(control),
                        requiredness(control),
                        selectorFor(name),
                        List.of()));
            }
            // Custom per-posting questions: Lever embeds each "card" (a themed
            // group of one or more questions) as a JSON blob in a hidden
            // input's value, named cards[<cardId>][baseTemplate]. The actual
            // answer controls for that card are cards[<cardId>][fieldN].
            for (Element hidden : document.select("input[type=hidden][name$=\"[baseTemplate]\"]")) {
                String name = hidden.attr("name");
                java.util.regex.Matcher m = Pattern.compile("^cards\\[([0-9a-fA-F-]{36})]\\[baseTemplate]$").matcher(name);
                if (!m.matches()) continue;
                String cardId = m.group(1);
                fields.addAll(parseCard(cardId, hidden.attr("value")));
            }
            return fields;
        }

        private static List<AtsAdapter.FormFieldDescriptor> parseCard(String cardId, String json) {
            List<AtsAdapter.FormFieldDescriptor> out = new ArrayList<>();
            try {
                com.fasterxml.jackson.databind.JsonNode card =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                com.fasterxml.jackson.databind.JsonNode questions = card.path("fields");
                int index = 0;
                for (com.fasterxml.jackson.databind.JsonNode q : questions) {
                    String key = "cards[" + cardId + "][field" + index + "]";
                    String type = q.path("type").asText("");
                    boolean required = q.path("required").asBoolean(false);
                    List<String> options = new ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode opt : q.path("options")) {
                        String text = opt.path("text").asText(null);
                        if (text != null && !text.isBlank()) options.add(text);
                    }
                    String htmlType = switch (type) {
                        case "multiple-select" -> "checkbox";
                        case "dropdown" -> "select";
                        case "radio" -> "radio";
                        case "textarea" -> "textarea";
                        default -> "text";
                    };
                    out.add(new AtsAdapter.FormFieldDescriptor(key, q.path("text").asText(null), htmlType,
                            required ? AtsAdapter.RequiredState.REQUIRED : AtsAdapter.RequiredState.OPTIONAL,
                            selectorFor(key), options));
                    index++;
                }
            } catch (Exception ignored) {
                // A card this parser cannot read contributes no fields rather
                // than guessing at its shape — the question then simply never
                // appears, and any later gap-detection sees it as absent
                // metadata rather than a fabricated one.
            }
            return out;
        }

        /** Lever's selectors are name-addressed, never id-addressed (unlike Greenhouse). */
        private static String selectorFor(String name) {
            return "[name=\"" + name + "\"]";
        }

        private static String htmlType(Element control) {
            if (control.tagName().equals("textarea")) return "textarea";
            if (control.tagName().equals("select")) return "select";
            String type = control.attr("type");
            return type.isBlank() ? "text" : type.toLowerCase(Locale.ROOT);
        }

        private static String labelFor(Element control, String fallbackKey) {
            String dataQa = control.attr("data-qa");
            if (!dataQa.isBlank()) return dataQa.replace("-input", "").replace("-", " ");
            return fallbackKey;
        }

        /** Lever renders {@code required} directly on the control — no sibling-mirror heuristic needed. */
        private static AtsAdapter.RequiredState requiredness(Element control) {
            return control.hasAttr("required") ? AtsAdapter.RequiredState.REQUIRED : AtsAdapter.RequiredState.OPTIONAL;
        }
    }

    @Component
    public static class WorkdayAdapter extends BaseAtsAdapter {
        /**
         * Workday serves tenants under two real host shapes: the
         * data-center-qualified form the discovery-side {@code WorkdayAdapter}
         * already verifies live postings against
         * ({@code tenant.wdN.myworkdayjobs.com}), and a bare tenant form with
         * no data-center infix ({@code tenant.myworkdayjobs.com}) — both
         * genuinely used (the existing {@code AtsAdapterRegistryTest} fixture
         * for "revolut.myworkdayjobs.com" is the bare form). Tightened from
         * the previous loose substring pattern (which also matched unrelated
         * {@code workday.com} marketing/community hosts) to this exact,
         * host-anchored check — the same rigor Greenhouse/Lever already have.
         * {@code requiresAuth=true, multiStep=true} were already correct:
         * confirmed live (Phase 7) — the apply form is reached only after a
         * Workday-hosted sign-in, and its own form is rendered entirely
         * client-side after that.
         */
        private static final java.util.regex.Pattern HOST =
                java.util.regex.Pattern.compile("^([a-z0-9-]{1,64})\\.(?:(wd\\d{1,3})\\.)?myworkdayjobs\\.com$");

        public WorkdayAdapter() {
            super(AtsKind.WORKDAY, HOST, true, true);
        }

        @Override
        public boolean matchesUrl(String url) {
            if (url == null || url.isBlank()) return false;
            try {
                java.net.URI uri = java.net.URI.create(url);
                String host = uri.getHost();
                return host != null && "https".equalsIgnoreCase(uri.getScheme())
                        && (uri.getPort() == -1 || uri.getPort() == 443)
                        && uri.getUserInfo() == null
                        && HOST.matcher(host.toLowerCase(java.util.Locale.ROOT)).matches();
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }

    @Component
    public static class IcimsAdapter extends BaseAtsAdapter {
        public IcimsAdapter() {
            super(AtsKind.ICIMS, Pattern.compile("icims\\.com"), true, true);
        }
    }

    @Component
    public static class BambooHrAdapter extends BaseAtsAdapter {
        public BambooHrAdapter() {
            super(AtsKind.BAMBOOHR, Pattern.compile("bamboohr\\.com"), false, false);
        }
    }

    @Component
    public static class WorkableAdapter extends BaseAtsAdapter {
        public WorkableAdapter() {
            super(AtsKind.WORKABLE, Pattern.compile("apply\\.workable\\.com|workable\\.com"), false, false);
        }
    }

    @Component
    public static class JobviteAdapter extends BaseAtsAdapter {
        public JobviteAdapter() {
            super(AtsKind.JOBVITE, Pattern.compile("jobs\\.jobvite\\.com|jobvite\\.com"), false, true);
        }
    }

    @Component
    public static class BreezyHrAdapter extends BaseAtsAdapter {
        public BreezyHrAdapter() {
            super(AtsKind.BREEZYHR, Pattern.compile("breezy\\.hr"), false, false);
        }
    }

    @Component
    public static class OracleCloudAdapter extends BaseAtsAdapter {
        public OracleCloudAdapter() {
            super(AtsKind.ORACLE_CLOUD, Pattern.compile("oraclecloud\\.com|oracle\\.com"), true, true);
        }
    }

    @Component
    public static class PaylocityAdapter extends BaseAtsAdapter {
        public PaylocityAdapter() {
            super(AtsKind.PAYLOCITY, Pattern.compile("paylocity\\.com"), false, true);
        }
    }

    @Component
    public static class UkgAdapter extends BaseAtsAdapter {
        public UkgAdapter() {
            super(AtsKind.UKG, Pattern.compile("ultipro\\.com|ukg\\.com"), true, true);
        }
    }

    @Component
    public static class AdpAdapter extends BaseAtsAdapter {
        public AdpAdapter() {
            super(AtsKind.ADP, Pattern.compile("workforcenow\\.adp\\.com|adp\\.com"), true, true);
        }
    }

    @Component
    public static class DoverAdapter extends BaseAtsAdapter {
        public DoverAdapter() {
            super(AtsKind.DOVER, Pattern.compile("dover\\.com|app\\.dover\\.io"), false, false);
        }
    }

    @Component
    public static class GemAdapter extends BaseAtsAdapter {
        public GemAdapter() {
            super(AtsKind.GEM, Pattern.compile("gem\\.com"), false, false);
        }
    }

    @Component
    public static class ZohoAdapter extends BaseAtsAdapter {
        public ZohoAdapter() {
            // The base-class pattern is retained for inspectForm()'s generic
            // field list and as the fallback this constructor still wires up;
            // matchesUrl below is overridden because the plain substring
            // pattern alone would miss zohorecruit.in (used by the majority
            // of sampled Indian Zoho tenants — discovery's own JobIdentity
            // already recognises these postings by URL path shape,
            // independent of host, so APPLY and discovery previously
            // disagreed on the exact same URL) and, separately, because a
            // bare substring match is weaker than this host-anchored check.
            super(AtsKind.ZOHO, Pattern.compile("zohorecruit\\.com|zohorecruit\\.in|zoho\\.com"), false, false);
        }

        @Override
        public boolean matchesUrl(String url) {
            if (url == null || url.isBlank()) return false;
            try {
                java.net.URI uri = java.net.URI.create(url);
                String host = uri.getHost();
                if (host == null || !"https".equalsIgnoreCase(uri.getScheme())
                        || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null) {
                    return false;
                }
                String h = host.toLowerCase(java.util.Locale.ROOT);
                return h.equals("zohorecruit.com") || h.endsWith(".zohorecruit.com")
                        || h.equals("zohorecruit.in") || h.endsWith(".zohorecruit.in")
                        || h.equals("zoho.com") || h.endsWith(".zoho.com");
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }

    @Component
    public static class RipplingAdapter extends BaseAtsAdapter {
        public RipplingAdapter() {
            super(AtsKind.RIPPLING, Pattern.compile("ats\\.rippling\\.com|rippling\\.com"), false, true);
        }
    }
}