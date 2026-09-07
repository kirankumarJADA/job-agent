package com.personal.jobagent.jobs;

import com.personal.jobagent.common.UuidV7;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Seeds ~25 realistic, high-quality UK tech job postings across top UK employers
 * (Monzo, Deliveroo, Skyscanner, Wise, Revolut, GoCardless, Starling Bank, Snyk, Synthesia, Improbable)
 * on local profile startup if the database contains only initial demo rows.
 */
@Service
@Profile("local")
public class JobSeedService {

    private static final Logger log = LoggerFactory.getLogger(JobSeedService.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.seed.uk-jobs.enabled:true}")
    private boolean seedEnabled;

    public JobSeedService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void onStartup() {
        if (!seedEnabled) {
            return;
        }
        try {
            Integer count = jdbcTemplate.queryForObject("select count(*) from jobs", Integer.class);
            if (count != null && count <= 2) {
                log.info("Database contains {} jobs; seeding 25 realistic UK tech job postings...", count);
                seedRealUkJobs();
            }
        } catch (Exception e) {
            log.warn("Job seeding skipped or failed: {}", e.getMessage());
        }
    }

    @Transactional
    public int seedRealUkJobs() {
        int inserted = 0;

        // 1. Ensure Companies Exist
        UUID monzoCo = upsertCompany("monzo", "Monzo Bank", "https://monzo.com/careers", "Fintech", "LARGE", "GB");
        UUID delivCo = upsertCompany("deliveroo", "Deliveroo", "https://deliveroo.engineering", "Logistics & Tech", "LARGE", "GB");
        UUID skyCo = upsertCompany("skyscanner", "Skyscanner", "https://www.skyscanner.net/jobs", "Travel Tech", "LARGE", "GB");
        UUID gcCo = upsertCompany("gocardless", "GoCardless", "https://gocardless.com/careers", "Fintech", "MID", "GB");
        UUID revCo = upsertCompany("revolut", "Revolut Ltd", "https://www.revolut.com/careers", "Fintech", "LARGE", "GB");
        UUID wiseCo = upsertCompany("wise", "Wise Payments Ltd", "https://wise.jobs", "Fintech", "LARGE", "GB");
        UUID starlingCo = upsertCompany("starling-bank", "Starling Bank", "https://www.starlingbank.com/careers", "Fintech", "MID", "GB");
        UUID snykCo = upsertCompany("snyk", "Snyk", "https://snyk.io/careers", "Cybersecurity", "MID", "GB");
        UUID synthCo = upsertCompany("synthesia", "Synthesia", "https://www.synthesia.io/careers", "AI & Media", "MID", "GB");
        UUID impCo = upsertCompany("improbable", "Improbable", "https://improbable.io/careers", "Simulation & Gaming", "MID", "GB");

        // 2. Ensure Sources Exist
        UUID monzoSrc = upsertSource("GREENHOUSE", "monzo", monzoCo, "Monzo (Greenhouse)");
        UUID delivSrc = upsertSource("GREENHOUSE", "deliveroo", delivCo, "Deliveroo (Greenhouse)");
        UUID skySrc = upsertSource("LEVER", "skyscanner", skyCo, "Skyscanner (Lever)");
        UUID gcSrc = upsertSource("GREENHOUSE", "gocardless", gcCo, "GoCardless (Greenhouse)");
        UUID revSrc = upsertSource("GREENHOUSE", "revolut", revCo, "Revolut (Greenhouse)");
        UUID wiseSrc = upsertSource("SMARTRECRUITERS", "wise", wiseCo, "Wise (SmartRecruiters)");
        UUID starlingSrc = upsertSource("GREENHOUSE", "starlingbank", starlingCo, "Starling (Greenhouse)");
        UUID snykSrc = upsertSource("GREENHOUSE", "snyk", snykCo, "Snyk (Greenhouse)");
        UUID synthSrc = upsertSource("ASHBY", "synthesia", synthCo, "Synthesia (Ashby)");
        UUID impSrc = upsertSource("LEVER", "improbable", impCo, "Improbable (Lever)");

        // 3. Insert 25 UK Postings
        inserted += insertJob(monzoSrc, "monzo-101", monzoCo, "Monzo Bank",
                "Senior Backend Engineer - Payments Core", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                85000, 110000, "GBP",
                "Design and operate highly reliable banking microservices handling millions of UK Faster Payments transactions daily. Built with Go, AWS, and Cassandra.",
                new String[]{"Go", "Kubernetes", "AWS", "Cassandra", "Distributed Systems"},
                "https://boards.greenhouse.io/monzo/jobs/5001", "DISCOVERED");

        inserted += insertJob(monzoSrc, "monzo-102", monzoCo, "Monzo Bank",
                "Staff Platform Engineer - Kubernetes", "London or Remote (UK)", "London", "GB", "REMOTE", "FULL_TIME", "LEAD",
                100000, 130000, "GBP",
                "Lead Monzo multi-cloud Kubernetes orchestration platform powering over 2,500 backend microservices. Deep Linux kernel and eBPF observability.",
                new String[]{"Kubernetes", "Go", "Terraform", "eBPF", "AWS"},
                "https://boards.greenhouse.io/monzo/jobs/5002", "DISCOVERED");

        inserted += insertJob(monzoSrc, "monzo-103", monzoCo, "Monzo Bank",
                "Backend Software Engineer - Financial Crime", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "MID",
                75000, 95000, "GBP",
                "Build automated fraud and AML detection pipelines operating with low latency on real-time ledger events. Java/Go backend with Kafka.",
                new String[]{"Go", "Java", "Kafka", "PostgreSQL"},
                "https://boards.greenhouse.io/monzo/jobs/5003", "DISCOVERED");

        inserted += insertJob(delivSrc, "deliv-201", delivCo, "Deliveroo",
                "Senior Data Platform Engineer", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                80000, 105000, "GBP",
                "Architect real-time order tracking and dispatch data pipelines with Kafka, Apache Flink, and Snowflake. Deliver high-throughput telemetry.",
                new String[]{"Kafka", "Flink", "Python", "Snowflake", "AWS"},
                "https://boards.greenhouse.io/deliveroo/jobs/6001", "DISCOVERED");

        inserted += insertJob(delivSrc, "deliv-202", delivCo, "Deliveroo",
                "Senior Full Stack Engineer - Rider Experience", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                75000, 95000, "GBP",
                "Build rider dispatch interfaces and partner portals using React, TypeScript, and Go microservices. Focus on accessibility and offline resilience.",
                new String[]{"TypeScript", "React", "Go", "GraphQL", "PostgreSQL"},
                "https://boards.greenhouse.io/deliveroo/jobs/6002", "DISCOVERED");

        inserted += insertJob(delivSrc, "deliv-203", delivCo, "Deliveroo",
                "Staff Distributed Systems Architect", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "LEAD",
                120000, 150000, "GBP",
                "Lead technical strategy for core delivery dispatch engine under peak load. Architect resilient microservice topologies on AWS EKS.",
                new String[]{"Go", "Java", "Distributed Systems", "AWS", "High Availability"},
                "https://boards.greenhouse.io/deliveroo/jobs/6003", "DISCOVERED");

        inserted += insertJob(skySrc, "sky-301", skyCo, "Skyscanner",
                "Senior Backend Engineer - Flight Search", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                75000, 95000, "GBP",
                "Power real-time flight query aggregation connecting hundreds of airlines worldwide. Built with Java 21, Spring Boot, and AWS.",
                new String[]{"Java", "Spring Boot", "AWS", "Kafka", "Microservices"},
                "https://jobs.lever.co/skyscanner/7001", "DISCOVERED");

        inserted += insertJob(skySrc, "sky-302", skyCo, "Skyscanner",
                "Staff Site Reliability Engineer", "Edinburgh, UK", "Edinburgh", "GB", "HYBRID", "FULL_TIME", "LEAD",
                90000, 115000, "GBP",
                "Ensure five-nines availability across international flight search services. Own automated incident remediation and chaos engineering.",
                new String[]{"Kubernetes", "Terraform", "Python", "Prometheus", "Datadog"},
                "https://jobs.lever.co/skyscanner/7002", "DISCOVERED");

        inserted += insertJob(skySrc, "sky-303", skyCo, "Skyscanner",
                "Senior Machine Learning Engineer - Pricing", "Remote (UK)", null, "GB", "REMOTE", "FULL_TIME", "SENIOR",
                80000, 100000, "GBP",
                "Productionize predictive dynamic pricing models processing billions of daily flight fare updates. Python, PyTorch, Ray, AWS SageMaker.",
                new String[]{"Python", "PyTorch", "AWS", "Machine Learning", "Docker"},
                "https://jobs.lever.co/skyscanner/7003", "DISCOVERED");

        inserted += insertJob(gcSrc, "gc-401", gcCo, "GoCardless",
                "Senior Software Engineer - Direct Debit Core", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                85000, 105000, "GBP",
                "Process billions in Bacs, SEPA, and Autogiro bank payments. Modernize payment orchestration services using Ruby, Go, and PostgreSQL.",
                new String[]{"Go", "PostgreSQL", "Kafka", "Ruby", "GCP"},
                "https://boards.greenhouse.io/gocardless/jobs/8001", "DISCOVERED");

        inserted += insertJob(gcSrc, "gc-402", gcCo, "GoCardless",
                "Platform Security Engineer", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "MID",
                80000, 100000, "GBP",
                "Embed automated security scanning in CI/CD, secure container supply chains, and harden Google Cloud Kubernetes clusters.",
                new String[]{"GCP", "Kubernetes", "Terraform", "Security", "Python"},
                "https://boards.greenhouse.io/gocardless/jobs/8002", "DISCOVERED");

        inserted += insertJob(revSrc, "rev-501", revCo, "Revolut Ltd",
                "Staff Backend Engineer - Global Treasury", "London or Remote (UK)", "London", "GB", "REMOTE", "FULL_TIME", "LEAD",
                110000, 140000, "GBP",
                "Develop ultra-low latency FX exchange and liquidity management engines with Java 21, Aeron, and PostgreSQL.",
                new String[]{"Java", "Spring Boot", "PostgreSQL", "Kafka", "Low Latency"},
                "https://boards.greenhouse.io/revolut/jobs/9001", "DISCOVERED");

        inserted += insertJob(revSrc, "rev-502", revCo, "Revolut Ltd",
                "Senior Golang Engineer - Crypto Trading", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                95000, 120000, "GBP",
                "Build high-frequency order book and settlement integration services for retail and institutional crypto trading.",
                new String[]{"Go", "gRPC", "Redis", "PostgreSQL", "Docker"},
                "https://boards.greenhouse.io/revolut/jobs/9002", "DISCOVERED");

        inserted += insertJob(revSrc, "rev-503", revCo, "Revolut Ltd",
                "Senior SRE / Cloud Infrastructure", "Remote (UK)", null, "GB", "REMOTE", "FULL_TIME", "SENIOR",
                90000, 115000, "GBP",
                "Scale distributed multi-region infrastructure on Google Cloud and AWS. Architect automated failover and zero-downtime database upgrades.",
                new String[]{"Terraform", "Kubernetes", "GCP", "AWS", "Bash"},
                "https://boards.greenhouse.io/revolut/jobs/9003", "DISCOVERED");

        inserted += insertJob(wiseSrc, "wise-601", wiseCo, "Wise Payments Ltd",
                "Senior Java Platform Engineer", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                85000, 110000, "GBP",
                "Build real-time cross-border settlements engine moving money across 50+ central banks. Spring Boot, Kafka, and PostgreSQL at scale.",
                new String[]{"Java", "Spring Boot", "Kafka", "PostgreSQL", "AWS"},
                "https://wise.jobs/jobs/1101", "DISCOVERED");

        inserted += insertJob(wiseSrc, "wise-602", wiseCo, "Wise Payments Ltd",
                "Backend Engineer - Compliance & AML", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "MID",
                70000, 90000, "GBP",
                "Design real-time transaction monitoring workflows to protect millions of international transfers against sanctioned entities.",
                new String[]{"Java", "Python", "PostgreSQL", "Docker"},
                "https://wise.jobs/jobs/1102", "DISCOVERED");

        inserted += insertJob(wiseSrc, "wise-603", wiseCo, "Wise Payments Ltd",
                "Senior Frontend Engineer - Design Systems", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                75000, 95000, "GBP",
                "Lead development of the global Neptune design system in React and TypeScript across web and mobile web apps.",
                new String[]{"TypeScript", "React", "CSS", "Design Systems", "Web Accessibility"},
                "https://wise.jobs/jobs/1103", "DISCOVERED");

        inserted += insertJob(starlingSrc, "star-701", starlingCo, "Starling Bank",
                "Senior Backend Engineer - Cloud Banking Core", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                80000, 100000, "GBP",
                "Engineer 100% cloud-native retail and business current account services with Java, Spring Boot, and AWS DynamoDB/Aurora.",
                new String[]{"Java", "Spring Boot", "AWS", "Aurora", "Microservices"},
                "https://boards.greenhouse.io/starlingbank/jobs/1201", "DISCOVERED");

        inserted += insertJob(starlingSrc, "star-702", starlingCo, "Starling Bank",
                "Lead Platform & Cloud Infrastructure Engineer", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "LEAD",
                95000, 120000, "GBP",
                "Own Starling cloud networking, IAM governance, and infrastructure-as-code automation on AWS. Enforce strict banking compliance.",
                new String[]{"AWS", "Terraform", "Linux", "Kubernetes", "Security"},
                "https://boards.greenhouse.io/starlingbank/jobs/1202", "DISCOVERED");

        inserted += insertJob(snykSrc, "snyk-801", snykCo, "Snyk",
                "Senior Security Software Engineer - Engine", "Remote (UK)", null, "GB", "REMOTE", "FULL_TIME", "SENIOR",
                90000, 115000, "GBP",
                "Build static code analysis algorithms and vulnerability detection rules for open-source libraries. TypeScript, Node.js, and Rust.",
                new String[]{"TypeScript", "Rust", "Node.js", "AppSec", "Docker"},
                "https://boards.greenhouse.io/snyk/jobs/1301", "DISCOVERED");

        inserted += insertJob(synthSrc, "synth-901", synthCo, "Synthesia",
                "Staff Backend Engineer - Deep Learning Pipeline", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "LEAD",
                105000, 135000, "GBP",
                "Scale video generation rendering clusters. Coordinate high-throughput GPU inference pipelines using Python, C++, and Kubernetes.",
                new String[]{"Python", "Kubernetes", "Docker", "GPU", "C++"},
                "https://jobs.ashbyhq.com/synthesia/1401", "DISCOVERED");

        inserted += insertJob(synthSrc, "synth-902", synthCo, "Synthesia",
                "Machine Learning Systems Engineer", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                90000, 120000, "GBP",
                "Optimize diffusion and generative audio models for real-time video avatar synthesis. TensorRT, PyTorch, and CUDA.",
                new String[]{"PyTorch", "CUDA", "Python", "TensorRT", "Machine Learning"},
                "https://jobs.ashbyhq.com/synthesia/1402", "DISCOVERED");

        inserted += insertJob(impSrc, "imp-1001", impCo, "Improbable",
                "Senior Distributed Systems Engineer - Spatial Engine", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                90000, 120000, "GBP",
                "Design distributed physics simulation engines handling 10,000+ concurrent players in shared virtual worlds. Modern C++ and Go.",
                new String[]{"C++", "Go", "Distributed Systems", "Networking", "gRPC"},
                "https://jobs.lever.co/improbable/1501", "DISCOVERED");

        inserted += insertJob(impSrc, "imp-1002", impCo, "Improbable",
                "Cloud Infrastructure Engineer", "Remote (UK)", null, "GB", "REMOTE", "FULL_TIME", "MID",
                75000, 95000, "GBP",
                "Deploy and manage global low-latency edge game servers across multiple public clouds and bare-metal providers with Terraform.",
                new String[]{"Terraform", "Kubernetes", "Linux", "GCP", "AWS"},
                "https://jobs.lever.co/improbable/1502", "DISCOVERED");

        inserted += insertJob(monzoSrc, "monzo-104", monzoCo, "Monzo Bank",
                "Software Engineer - Lending Core", "London, UK", "London", "GB", "HYBRID", "FULL_TIME", "MID",
                70000, 88000, "GBP",
                "Develop Monzo Flex, overdraft, and personal loan management systems with real-time decisioning and accounting reconciliations.",
                new String[]{"Go", "PostgreSQL", "Distributed Systems", "Microservices"},
                "https://boards.greenhouse.io/monzo/jobs/5004", "DISCOVERED");

        log.info("Successfully seeded {} realistic UK job postings.", inserted);
        return inserted;
    }

    private UUID upsertCompany(String slug, String name, String careersUrl, String industry, String sizeBucket, String hqCountry) {
        UUID id = UuidV7.generate();
        jdbcTemplate.update("""
                insert into companies (id, slug, name, careers_url, industry, size_bucket, hq_country)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (slug) do update set
                    name = excluded.name,
                    careers_url = excluded.careers_url,
                    industry = excluded.industry
                """, id, slug, name, careersUrl, industry, sizeBucket, hqCountry);

        return jdbcTemplate.queryForObject("select id from companies where slug = ?", UUID.class, slug);
    }

    private UUID upsertSource(String kind, String orgId, UUID companyId, String displayName) {
        UUID id = UuidV7.generate();
        jdbcTemplate.update("""
                insert into job_sources (id, kind, org_identifier, company_id, display_name, capabilities, policy)
                values (?, ?, ?, ?, ?, '{"discovery": true, "api": true, "automation": false}'::jsonb, 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do update set
                    display_name = excluded.display_name,
                    company_id = excluded.company_id
                """, id, kind, orgId, companyId, displayName);

        return jdbcTemplate.queryForObject(
                "select id from job_sources where kind = ? and org_identifier = ?", UUID.class, kind, orgId);
    }

    private int insertJob(UUID sourceId, String externalId, UUID companyId, String companyName,
                          String title, String locationRaw, String city, String country,
                          String remoteType, String employmentType, String experienceLevel,
                          int salaryMin, int salaryMax, String currency,
                          String description, String[] skills, String appUrl, String status) {

        String dedupKey = md5(companyName.toLowerCase() + "|" + title.toLowerCase() + "|" + locationRaw.toLowerCase());
        String contentHash = md5(title + "|" + description);
        UUID jobId = UuidV7.generate();

        return jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (var ps = conn.prepareStatement("""
                    insert into jobs (
                        id, source_id, external_id, dedup_key, company_id, company_name_raw, title,
                        location_raw, city, country, remote_type, employment_type, experience_level,
                        salary_min, salary_max, salary_currency, salary_period, description_text,
                        skills_extracted, application_url, canonical_url, posted_at, posted_date_source,
                        content_hash, status
                    ) values (
                        ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, 'YEAR', ?,
                        ?, ?, ?, now() - interval '2 days', 'EXPLICIT',
                        ?, ?
                    )
                    on conflict (source_id, external_id) do nothing
                    """)) {

                ps.setObject(1, jobId);
                ps.setObject(2, sourceId);
                ps.setString(3, externalId);
                ps.setString(4, dedupKey);
                ps.setObject(5, companyId);
                ps.setString(6, companyName);
                ps.setString(7, title);
                ps.setString(8, locationRaw);
                ps.setString(9, city);
                ps.setString(10, country);
                ps.setString(11, remoteType);
                ps.setString(12, employmentType);
                ps.setString(13, experienceLevel);
                ps.setInt(14, salaryMin);
                ps.setInt(15, salaryMax);
                ps.setString(16, currency);
                ps.setString(17, description);
                ps.setArray(18, conn.createArrayOf("text", skills));
                ps.setString(19, appUrl);
                ps.setString(20, appUrl);
                ps.setString(21, contentHash);
                ps.setString(22, status);

                return ps.executeUpdate();
            } catch (Exception e) {
                log.warn("Failed to insert job {}: {}", title, e.getMessage());
                return 0;
            }
        });
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
