package com.personal.jobagent.ats;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

public final class AtsAdapters {

    @Component
    public static class GreenhouseAdapter extends BaseAtsAdapter {
        public GreenhouseAdapter() {
            super(AtsKind.GREENHOUSE, Pattern.compile("boards\\.greenhouse\\.io|greenhouse\\.io"), false, false);
        }
    }

    @Component
    public static class LeverAdapter extends BaseAtsAdapter {
        public LeverAdapter() {
            super(AtsKind.LEVER, Pattern.compile("jobs\\.lever\\.co|lever\\.co"), false, false);
        }
    }

    @Component
    public static class AshbyAdapter extends BaseAtsAdapter {
        public AshbyAdapter() {
            super(AtsKind.ASHBY, Pattern.compile("jobs\\.ashbyhq\\.com|ashbyhq\\.com"), false, false);
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
            super(AtsKind.ZOHO, Pattern.compile("zohorecruit\\.com|zoho\\.com"), false, false);
        }
    }

    @Component
    public static class RipplingAdapter extends BaseAtsAdapter {
        public RipplingAdapter() {
            super(AtsKind.RIPPLING, Pattern.compile("ats\\.rippling\\.com|rippling\\.com"), false, true);
        }
    }
}