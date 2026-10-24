package com.personal.jobagent.discovery.platform;

import com.personal.jobagent.discovery.AshbyProvider;
import com.personal.jobagent.discovery.GreenhouseProvider;
import com.personal.jobagent.discovery.platform.adapters.AdzunaAdapter;
import com.personal.jobagent.discovery.platform.adapters.ArbeitnowAdapter;
import com.personal.jobagent.discovery.platform.adapters.DetailFetchAdapters;
import com.personal.jobagent.discovery.platform.adapters.FreeHireAdapter;
import com.personal.jobagent.discovery.platform.adapters.LeverAdapter;
import com.personal.jobagent.discovery.platform.adapters.MarkupAdapters;
import com.personal.jobagent.discovery.platform.adapters.ProviderBackedAdapter;
import com.personal.jobagent.discovery.platform.adapters.RipplingAdapter;
import com.personal.jobagent.discovery.platform.adapters.SinglePayloadAdapters;
import com.personal.jobagent.discovery.platform.adapters.TsentaJobSource;
import com.personal.jobagent.discovery.platform.adapters.WorkdayAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * THE source registry for board-based discovery: every adapter is registered
 * here exactly once, keyed by {@link PlatformKind}. There is no other
 * registry — the scheduler, the health check, {@code /discovery/run} and the
 * URL import all resolve adapters through this class.
 *
 * <p>Only platforms whose readiness is {@code LIVE_VALIDATED} have an adapter
 * (enforced by {@code PlatformCoverageAuditTest}). Unverified and blocked
 * platforms are still RECOGNISED from their URLs so an import can tell the
 * candidate exactly which platform it is and why it cannot be fetched yet.
 */
@Component
public class PlatformRegistry {

    private final Map<PlatformKind, BoardAdapter> adapters = new EnumMap<>(PlatformKind.class);

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformRegistry(@Value("${app.discovery.tsenta.max-details-per-run:40}") int tsentaMaxDetails,
                            @Value("${app.discovery.adzuna.app-id:}") String adzunaAppId,
                            @Value("${app.discovery.adzuna.app-key:}") String adzunaAppKey) {
        this(new GreenhouseProvider(), new AshbyProvider(), tsentaMaxDetails, adzunaAppId, adzunaAppKey);
    }

    public PlatformRegistry(GreenhouseProvider greenhouse, AshbyProvider ashby, int tsentaMaxDetails) {
        this(greenhouse, ashby, tsentaMaxDetails, "", "");
    }

    public PlatformRegistry(int tsentaMaxDetails) {
        this(new GreenhouseProvider(), new AshbyProvider(), tsentaMaxDetails, "", "");
    }

    public PlatformRegistry(GreenhouseProvider greenhouse, AshbyProvider ashby, int tsentaMaxDetails,
                            String adzunaAppId, String adzunaAppKey) {
        register(new WorkdayAdapter());
        register(new ProviderBackedAdapter.Greenhouse(greenhouse));
        register(new LeverAdapter());
        register(new ProviderBackedAdapter.Ashby(ashby));
        register(new RipplingAdapter());
        register(new MarkupAdapters.Icims());
        register(new SinglePayloadAdapters.Workable());
        register(new MarkupAdapters.JazzHr());
        register(new MarkupAdapters.Jobvite());
        register(new DetailFetchAdapters.BreezyHr());
        register(new DetailFetchAdapters.OracleCloud());
        register(new DetailFetchAdapters.SmartRecruiters());
        register(new DetailFetchAdapters.Paylocity());
        register(new DetailFetchAdapters.UltiPro());
        register(new SinglePayloadAdapters.Gem());
        register(new SinglePayloadAdapters.ZohoRecruit());
        register(new MarkupAdapters.Join());
        register(new DetailFetchAdapters.Phenom());
        register(new SinglePayloadAdapters.Pinpoint());
        register(new SinglePayloadAdapters.Recruitee());
        register(new MarkupAdapters.Teamtailor());
        register(new TsentaJobSource(tsentaMaxDetails));
        register(new FreeHireAdapter());
        register(new ArbeitnowAdapter());
        register(new AdzunaAdapter(adzunaAppId, adzunaAppKey));
    }

    private void register(BoardAdapter adapter) {
        if (adapters.put(adapter.kind(), adapter) != null) {
            throw new IllegalStateException("duplicate adapter for " + adapter.kind());
        }
    }

    public Optional<BoardAdapter> adapter(PlatformKind kind) {
        return Optional.ofNullable(adapters.get(kind));
    }

    public Map<PlatformKind, BoardAdapter> adapters() {
        return Map.copyOf(adapters);
    }

    /** What a URL is, as far as the registry can tell from the URL alone. */
    public record Recognition(PlatformKind kind, BoardAdapter.BoardRef board) {
        public boolean fetchable() {
            return board != null;
        }
    }

    /**
     * Platform (and, for supported platforms, board) for a URL. Recognition
     * failures of a supported platform's identifier (e.g. an invalid slug)
     * surface as {@link BoardConfigException}.
     */
    public Optional<Recognition> recognize(URI url) {
        for (BoardAdapter adapter : adapters.values()) {
            Optional<BoardAdapter.BoardRef> ref = adapter.recognize(url);
            if (ref.isPresent()) return Optional.of(new Recognition(adapter.kind(), ref.get()));
        }
        return identifyUnsupported(url).map(kind -> new Recognition(kind, null));
    }

    private static final Map<PlatformKind, Pattern> UNSUPPORTED_HOSTS = Map.of(
            PlatformKind.BAMBOOHR, Pattern.compile("^[a-z0-9-]+\\.bamboohr\\.com$"),
            PlatformKind.ADP, Pattern.compile("^(workforcenow|myjobs|recruiting)\\.adp\\.com$"),
            PlatformKind.DOVER, Pattern.compile("^app\\.dover\\.com$"),
            PlatformKind.BRASSRING, Pattern.compile("^([a-z0-9-]+-)?sjobs\\.brassring\\.com$"),
            PlatformKind.CAREERPLUG, Pattern.compile("^[a-z0-9-]+\\.careerplug\\.com$"),
            PlatformKind.DAYFORCE, Pattern.compile("^jobs\\.dayforcehcm\\.com$"),
            PlatformKind.HIREOLOGY, Pattern.compile("^(careers\\.hireology\\.com|[a-z0-9-]+\\.hireology\\.careers)$"),
            PlatformKind.POLYMER, Pattern.compile("^jobs\\.polymer\\.co$"),
            PlatformKind.SAP_SUCCESSFACTORS, Pattern.compile("^(career\\d*|[a-z0-9-]+)\\.(successfactors\\.(com|eu)|sapsf\\.(com|eu))$"));

    static Optional<PlatformKind> identifyUnsupported(URI url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("www.bamboohr.com")) return Optional.empty();
        for (Map.Entry<PlatformKind, Pattern> e : UNSUPPORTED_HOSTS.entrySet()) {
            if (e.getValue().matcher(host).matches()) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    public List<PlatformKind> supportedKinds() {
        return List.copyOf(adapters.keySet());
    }
}
