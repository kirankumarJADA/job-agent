package com.personal.jobagent.llm;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per architecture doc's validation gate item 4: "Ping endpoint can
 * simulate Gemini until then [real keys are supplied]." This provider is
 * always healthy and always available — it exists specifically so
 * /system/llm/ping and the router's fallback chain are testable before any
 * real API key exists, and so ModelRouter always has SOMETHING to fall
 * back to in the worst case.
 *
 * Deliberately NOT a stand-in for real quality evaluation — benchmark runs
 * (P1-f) should exclude this provider once real ones are configured, since
 * its "completions" are canned, not model output.
 */
@Component
public class SimulatedProvider implements LlmProvider {

    public static final String PROVIDER_ID = "simulated";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Set<String> supportedModels() {
        return Set.of("simulated-v1");
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public LlmCompletion complete(LlmCompletionRequest request, Duration timeout) {
        long start = System.currentTimeMillis();
        String prompt = "";
        if (request.messages() != null && !request.messages().isEmpty()) {
            prompt = request.messages().get(request.messages().size() - 1).getOrDefault("content", "");
        }
        String canned;
        if (prompt.contains(RESUME_OPEN)) {
            // Master Resume Import (feat/master-resume-import): a deterministic,
            // heuristic "extraction" over whatever resume text is embedded in the
            // prompt, so the full preview/confirm pipeline (including source
            // grounding, which checks extracted values against the original
            // text) is exercisable end to end before any real provider key
            // exists — exactly this class's stated purpose. Not a stand-in for
            // real extraction quality: callers detect this provider and flag
            // the result as an offline fallback.
            canned = simulateProfileExtraction(prompt);
        } else if (prompt.toLowerCase().contains("top secret") || prompt.toLowerCase().contains("quantum computing")) {
            // Hallucination-prone branch: when the question invites an
            // unverifiable claim, the simulated model "hallucinates" one —
            // this is what lets the anti-fabrication guard rails in
            // CoverLetterService/ApplicationAnswerService be exercised end
            // to end (hard stop + WARN notification) before real keys exist.
            canned = "I hold active top secret security clearance and have published research in quantum computing.";
        } else if (prompt.contains("cover letter") || prompt.contains("Cover Letter") || prompt.contains("Dear Hiring")) {
            canned = "Dear Hiring Team,\n\nI am writing to express my strong enthusiasm for this role. With my background in software engineering, distributed systems, and modern cloud technologies, I am confident in my ability to deliver immediate value to your engineering team.\n\nThroughout my career, I have consistently focused on building scalable, reliable architectures and driving engineering excellence. My verified technical experience directly matches your core requirements, and I am excited about the opportunity to contribute to your mission.\n\nThank you for considering my application. I look forward to the possibility of discussing how my skills and experience can support your team's success.\n\nSincerely,\nCandidate";
        } else if (prompt.contains("question") || prompt.contains("Question:") || prompt.contains("Why do you want") || prompt.contains("Why are you interested")) {
            canned = "I am deeply interested in this position because your mission aligns directly with my professional experience in building resilient, high-performance systems. Having worked extensively with modern engineering stacks and distributed architectures, I am eager to apply my background to help solve complex technical challenges at scale while collaborating with your world-class team.";
        } else {
            canned = "[simulated response for correlationId=" + request.correlationId() + "]";
        }
        long latency = System.currentTimeMillis() - start;
        return new LlmCompletion(canned, new LlmCompletion.TokenUsage(50, 120), latency, LlmCompletion.FinishReason.STOP);
    }

    // The resume text is user-controlled and this runs on the request thread,
    // so every step below is linear in its length: markers are found with
    // indexOf, fields are split rather than matched with nested lazy groups,
    // and the remaining patterns only ever see one line of at most MAX_LINE
    // characters (contact details: only the header lines).
    private static final String RESUME_OPEN = "<untrusted-resume-content>";
    private static final String RESUME_CLOSE = "</untrusted-resume-content>";
    private static final int MAX_LINE = 300;
    private static final int HEADER_LINES = 8;
    private static final Set<String> SECTIONS = Set.of("SUMMARY", "EXPERIENCE", "EDUCATION", "SKILLS", "CERTIFICATIONS", "PROJECTS");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9][0-9 ().-]{7,}[0-9]");
    private static final Pattern LINKEDIN = Pattern.compile("(?i)linkedin\\.com/\\S+");
    private static final Pattern WEBSITE = Pattern.compile("(?i)https?://\\S+");
    /** " — " or " - " between fields; a date's own hyphen ("2021-01") has no spaces around it. */
    private static final Pattern FIELD_SEPARATOR = Pattern.compile("\\s+[—-]\\s+");
    private static final Pattern DATE_RANGE = Pattern.compile(
            "(\\d{4}(?:-\\d{2})?)\\s*(?:to|–|-)\\s*(Present|\\d{4}(?:-\\d{2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("\\d{4}");

    /** "Title — Company — 2021-01 to 2023-12"-style line split into its fields, or null. */
    private record Fields(String first, String second, String last) {
    }

    /**
     * A small heuristic resume parser, intentionally scoped to the clean
     * "Title — Company — StartDate to EndDate" / section-header style used by
     * this codebase's own resume-import fixtures (see ResumeImportFixtures in
     * the backend tests) — not a general-purpose resume parser.
     */
    private String simulateProfileExtraction(String prompt) {
        int open = prompt.indexOf(RESUME_OPEN);
        int close = prompt.lastIndexOf(RESUME_CLOSE);
        String resume = open >= 0 && close > open ? prompt.substring(open + RESUME_OPEN.length(), close) : "";
        List<String> lines = new ArrayList<>();
        for (String line : resume.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && trimmed.length() <= MAX_LINE) lines.add(trimmed);
        }

        String fullName = lines.isEmpty() ? null : lines.get(0);
        String headline = lines.size() > 1 && !lines.get(1).contains("@") && !LINKEDIN.matcher(lines.get(1)).find()
                ? lines.get(1) : null;
        String email = null;
        String phone = null;
        String location = null;
        String linkedin = null;
        String website = null;
        for (String line : lines.subList(0, Math.min(HEADER_LINES, lines.size()))) {
            if (email == null) email = firstMatch(EMAIL, line);
            if (phone == null) phone = firstMatch(PHONE, line);
            if (linkedin == null) linkedin = firstMatch(LINKEDIN, line);
            if (website == null) {
                String candidate = firstMatch(WEBSITE, line);
                if (candidate != null && !candidate.toLowerCase(Locale.ROOT).contains("linkedin")) website = candidate;
            }
            if (location == null && line.contains("|")) {
                for (String part : line.split("\\|")) {
                    String candidate = part.strip();
                    if (candidate.isEmpty() || EMAIL.matcher(candidate).find() || PHONE.matcher(candidate).find()) continue;
                    location = candidate;
                    break;
                }
            }
        }

        StringBuilder summary = new StringBuilder();
        List<List<String>> experiences = new ArrayList<>();
        List<Fields> education = new ArrayList<>();
        List<String> skills = new ArrayList<>();
        List<Fields> certifications = new ArrayList<>();
        List<String> projects = new ArrayList<>();

        String section = null;
        List<String> currentExperience = null;
        for (String line : lines) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (SECTIONS.contains(upper)) {
                currentExperience = null;
                section = upper;
                continue;
            }
            if (section == null) continue;
            switch (section) {
                case "SUMMARY" -> summary.append(summary.isEmpty() ? "" : " ").append(line);
                case "EXPERIENCE" -> {
                    Fields header = fields(line);
                    if (header != null && DATE_RANGE.matcher(header.last()).matches()) {
                        currentExperience = new ArrayList<>(List.of(line));
                        experiences.add(currentExperience);
                    } else if (currentExperience != null) {
                        currentExperience.add(line);
                    }
                }
                case "EDUCATION" -> {
                    Fields entry = fields(line);
                    if (entry != null && DATE_RANGE.matcher(entry.last()).matches()) education.add(entry);
                }
                case "SKILLS" -> skills.add(line);
                case "CERTIFICATIONS" -> {
                    Fields entry = fields(line);
                    if (entry != null && YEAR.matcher(entry.last()).matches()) certifications.add(entry);
                }
                case "PROJECTS" -> projects.add(line);
                default -> { }
            }
        }

        StringBuilder json = new StringBuilder("{");
        json.append("\"personal\":{")
                .append("\"fullName\":").append(jsonString(fullName)).append(',')
                .append("\"headline\":").append(jsonString(headline)).append(',')
                .append("\"email\":").append(jsonString(email)).append(',')
                .append("\"phone\":").append(jsonString(phone)).append(',')
                .append("\"location\":").append(jsonString(location)).append(',')
                .append("\"linkedinUrl\":").append(jsonString(linkedin)).append(',')
                .append("\"websiteUrl\":").append(jsonString(website))
                .append("},");
        json.append("\"summary\":{")
                .append("\"professionalSummary\":").append(jsonString(summary.isEmpty() ? null : summary.toString())).append(',')
                .append("\"careerFocus\":null,\"specializations\":[]")
                .append("},");
        json.append("\"experiences\":[");
        for (int i = 0; i < experiences.size(); i++) {
            if (i > 0) json.append(',');
            json.append(experienceJson(experiences.get(i)));
        }
        json.append("],\"education\":[");
        for (int i = 0; i < education.size(); i++) {
            if (i > 0) json.append(',');
            json.append(educationJson(education.get(i)));
        }
        json.append("],\"projects\":[");
        for (int i = 0; i < projects.size(); i++) {
            if (i > 0) json.append(',');
            json.append("{\"name\":").append(jsonString(projects.get(i))).append(",\"description\":null,\"url\":null,")
                    .append("\"technologies\":[],\"achievements\":[]}");
        }
        json.append("],\"skills\":{\"technical\":[").append(skillsJson(skills))
                .append("],\"languages\":[],\"frameworks\":[],\"databases\":[],\"cloud\":[],\"tools\":[],\"other\":[]},");
        json.append("\"certifications\":[");
        for (int i = 0; i < certifications.size(); i++) {
            if (i > 0) json.append(',');
            json.append(certificationJson(certifications.get(i)));
        }
        json.append("]}");
        return json.toString();
    }

    /** Splits on spaced dashes; the last part is the date field, the one before it the second field, the rest the first. */
    private static Fields fields(String line) {
        String[] parts = FIELD_SEPARATOR.split(line);
        if (parts.length < 3) return null;
        String first = String.join(" - ", Arrays.copyOfRange(parts, 0, parts.length - 2)).strip();
        return new Fields(first, parts[parts.length - 2].strip(), parts[parts.length - 1].strip());
    }

    private String experienceJson(List<String> block) {
        Fields header = fields(block.get(0));
        Matcher dates = DATE_RANGE.matcher(header.last());
        dates.matches();
        String endRaw = dates.group(2);
        boolean current = endRaw.equalsIgnoreCase("Present");
        List<String> bullets = new ArrayList<>();
        String location = null;
        for (String line : block.subList(1, block.size())) {
            if (line.startsWith("-")) bullets.add(line.substring(1).strip());
            else if (location == null) location = line;
        }
        StringBuilder bulletJson = new StringBuilder();
        for (int i = 0; i < bullets.size(); i++) {
            if (i > 0) bulletJson.append(',');
            bulletJson.append(jsonString(bullets.get(i)));
        }
        return "{\"company\":" + jsonString(header.second()) + ",\"title\":" + jsonString(header.first())
                + ",\"location\":" + jsonString(location) + ",\"startDate\":" + jsonString(dates.group(1))
                + ",\"endDate\":" + jsonString(current ? null : endRaw) + ",\"current\":" + current
                + ",\"responsibilities\":[" + bulletJson + "],\"achievements\":[],\"technologies\":[]}";
    }

    private String educationJson(Fields entry) {
        Matcher years = DATE_RANGE.matcher(entry.last());
        years.matches();
        String end = years.group(2);
        return "{\"institution\":" + jsonString(entry.second()) + ",\"qualification\":" + jsonString(entry.first())
                + ",\"field\":null,\"startYear\":" + years.group(1).substring(0, 4)
                + ",\"endYear\":" + (end.equalsIgnoreCase("Present") ? "null" : end.substring(0, 4)) + ",\"grade\":null}";
    }

    private String certificationJson(Fields entry) {
        return "{\"name\":" + jsonString(entry.first()) + ",\"issuer\":" + jsonString(entry.second())
                + ",\"issueDate\":" + jsonString(entry.last()) + ",\"expirationDate\":null,\"credentialId\":null,\"verificationUrl\":null}";
    }

    private String skillsJson(List<String> lines) {
        List<String> names = new ArrayList<>();
        for (String line : lines) {
            for (String token : line.split(",")) {
                String name = token.strip();
                if (!name.isEmpty()) names.add(name);
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) out.append(',');
            out.append(jsonString(names.get(i)));
        }
        return out.toString();
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group() : null;
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
