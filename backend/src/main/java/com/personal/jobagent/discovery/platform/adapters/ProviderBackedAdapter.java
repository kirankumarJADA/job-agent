package com.personal.jobagent.discovery.platform.adapters;

import com.personal.jobagent.discovery.AshbyProvider;
import com.personal.jobagent.discovery.GreenhouseProvider;
import com.personal.jobagent.discovery.ScraperProvider;
import com.personal.jobagent.discovery.platform.BoardAdapter;
import com.personal.jobagent.discovery.platform.BoardConfigException;
import com.personal.jobagent.discovery.platform.FailureCategory;
import com.personal.jobagent.discovery.platform.NormalizedJob;
import com.personal.jobagent.discovery.platform.Normalize;
import com.personal.jobagent.discovery.platform.Parsing;
import com.personal.jobagent.discovery.platform.PlatformKind;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Greenhouse and Ashby were integrated before Phase 8.2.5 with their own
 * tested providers ({@link GreenhouseProvider}, {@link AshbyProvider}). These
 * adapters put them behind the common {@link BoardAdapter} contract instead
 * of re-implementing them, so there is still exactly one parser per platform.
 * Both public APIs return the whole board in one response, so a successful
 * call is a complete listing.
 */
public abstract class ProviderBackedAdapter implements BoardAdapter {

    protected abstract ScraperProvider.ExtractionResult extract(String boardName);

    @Override
    public ListingResult list(BoardRef board, CrawlContext context) {
        ScraperProvider.ExtractionResult result;
        try {
            result = extract(board.identifier());
        } catch (IllegalArgumentException e) {
            return ListingResult.failed(FailureCategory.CONFIGURATION, e.getMessage());
        }
        if (result.error() != null) {
            return ListingResult.failed(categorise(result.error()), result.error());
        }
        List<ListingEntry> entries = new ArrayList<>();
        for (ScraperProvider.ExtractedJob job : result.jobs()) {
            if (job.externalJobId() == null || job.title() == null) continue;
            entries.add(ListingEntry.complete(NormalizedJob.builder(job.externalJobId(), job.title())
                    .employer(Normalize.first(context.employerName(), job.company()))
                    .location(job.location())
                    .remote(job.remoteType() == null || "UNKNOWN".equals(job.remoteType()) ? null : job.remoteType())
                    .employmentType(job.employmentType())
                    .description(job.description() == null ? "" : job.description())
                    .applicationUrl(job.applicationUrl())
                    .sourceUrl(Normalize.first(job.sourceUrl(), job.applicationUrl()))
                    .postedAt(job.postedAt() != null && !job.postedAt().isAfter(context.now().plus(java.time.Duration.ofDays(1)))
                            ? job.postedAt() : null)
                    .skills(job.skills())
                    .build()));
        }
        return ListingResult.complete(entries, 1, context.employerName());
    }

    @Override
    public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
        throw new UnsupportedOperationException("listing entries are always complete");
    }

    static FailureCategory categorise(String error) {
        String e = error.toUpperCase(Locale.ROOT);
        if (e.contains("QUOTA") || e.contains("429")) return FailureCategory.RATE_LIMITED;
        if (e.contains("HTTP_404")) return FailureCategory.NOT_FOUND;
        if (e.contains("HTTP_401") || e.contains("HTTP_403")) return FailureCategory.ACCESS_DENIED;
        if (e.contains("HTTP_5")) return FailureCategory.UPSTREAM_ERROR;
        if (e.contains("TIMEOUT")) return FailureCategory.TIMEOUT;
        if (e.contains("MALFORMED") || e.contains("INCOMPLETE")) return FailureCategory.MALFORMED_RESPONSE;
        return FailureCategory.NETWORK;
    }

    /** Greenhouse public Job Board API (boards-api.greenhouse.io). */
    public static class Greenhouse extends ProviderBackedAdapter {
        private final GreenhouseProvider provider;

        public Greenhouse(GreenhouseProvider provider) {
            this.provider = provider;
        }

        @Override
        public PlatformKind kind() {
            return PlatformKind.GREENHOUSE;
        }

        @Override
        protected ScraperProvider.ExtractionResult extract(String boardName) {
            return provider.extractBoard(boardName, java.util.UUID.randomUUID().toString());
        }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            String host = Parsing.host(url);
            List<String> s = Parsing.pathSegments(url);
            if (host.equals("boards.greenhouse.io") || host.equals("job-boards.greenhouse.io")
                    || host.equals("job-boards.eu.greenhouse.io") || host.equals("boards.eu.greenhouse.io")) {
                if (!s.isEmpty() && !s.get(0).equals("embed")) return Optional.of(parseIdentifier(s.get(0)));
                String query = url.getRawQuery() == null ? "" : url.getRawQuery();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)for=([A-Za-z0-9_-]+)").matcher(query);
                if (m.find()) return Optional.of(parseIdentifier(m.group(1)));
            }
            if (host.equals("boards-api.greenhouse.io") && s.size() >= 3 && s.get(1).equals("boards")) {
                return Optional.of(parseIdentifier(s.get(2)));
            }
            return Optional.empty();
        }

        @Override
        public BoardRef parseIdentifier(String orgIdentifier) {
            String token = Parsing.requireSlug(orgIdentifier, "Greenhouse board token", "[A-Za-z0-9_-]{1,100}");
            return new BoardRef(kind(), token, Map.of());
        }
    }

    /** Ashby public Job Postings API (api.ashbyhq.com/posting-api/job-board). */
    public static class Ashby extends ProviderBackedAdapter {
        private final AshbyProvider provider;

        public Ashby(AshbyProvider provider) {
            this.provider = provider;
        }

        @Override
        public PlatformKind kind() {
            return PlatformKind.ASHBY;
        }

        @Override
        protected ScraperProvider.ExtractionResult extract(String boardName) {
            return provider.extractBoard(boardName, java.util.UUID.randomUUID().toString());
        }

        @Override
        public Optional<BoardRef> recognize(URI url) {
            if (!Parsing.host(url).equals("jobs.ashbyhq.com")) return Optional.empty();
            List<String> s = Parsing.pathSegments(url);
            return s.isEmpty() ? Optional.empty() : Optional.of(parseIdentifier(s.get(0)));
        }

        @Override
        public BoardRef parseIdentifier(String orgIdentifier) {
            try {
                String board = AshbyProvider.boardNameFromIdentifier(orgIdentifier);
                return new BoardRef(kind(), board, Map.of());
            } catch (IllegalArgumentException e) {
                throw new BoardConfigException("Ashby board name is not valid");
            }
        }
    }
}
