package com.personal.jobagent.ats;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

public final class AtsAdapters {

    // Greenhouse (real read-only inspection) and Ashby (honest detection
    // boundary — its apply forms are client-rendered) are top-level real
    // adapters; see GreenhouseAdapter and AshbyAdapter.

    @Component
    public static class LeverAdapter extends BaseAtsAdapter {
        public LeverAdapter() {
            super(AtsKind.LEVER, Pattern.compile("jobs\\.lever\\.co|lever\\.co"), false, false);
        }
    }

    @Component
    public static class WorkdayAdapter extends BaseAtsAdapter {
        public WorkdayAdapter() {
            super(AtsKind.WORKDAY, Pattern.compile("myworkdayjobs\\.com|workday\\.com"), true, true);
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