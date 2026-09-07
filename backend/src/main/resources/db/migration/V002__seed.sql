-- V002__seed.sql
-- Local/dev seed data only. Not intended for any shared or production
-- environment (real password hash below is a placeholder — see README).
-- Uses gen_random_uuid() for seed rows; application code generates UUIDv7
-- for all rows it creates at runtime (see common.UuidV7).

do $$
declare
  v_user_id       uuid := gen_random_uuid();
  v_profile_id    uuid := gen_random_uuid();
  v_pref_id       uuid := gen_random_uuid();
  v_company1_id   uuid := gen_random_uuid();
  v_company2_id   uuid := gen_random_uuid();
  v_source1_id    uuid := gen_random_uuid();
  v_source2_id    uuid := gen_random_uuid();
begin

  insert into users (id, email, password_hash, display_name)
  values (
    v_user_id,
    'dev@example.local',
    -- placeholder argon2id hash for local dev login only; rotate before any
    -- non-local use. Corresponds to no real password until you set one via
    -- the auth flow implemented in P1-b.
    '$argon2id$v=19$m=19456,t=2,p=1$cGxhY2Vob2xkZXJzYWx0$cGxhY2Vob2xkZXJoYXNo',
    'Dev User'
  );

  insert into profiles (id, user_id, headline, location, work_eligibility, career_goals)
  values (
    v_profile_id,
    v_user_id,
    'Backend Engineer',
    'London, UK',
    '{"right_to_work_uk": false, "visa_status": "requires_sponsorship"}'::jsonb,
    '{"summary": "Seeking sponsoring UK backend/platform roles"}'::jsonb
  );

  -- Scoring weights must sum to 100; validated again at load-time by the
  -- backend (see preferences module), this is just the seeded default.
  insert into preference_sets (
    id, profile_id, titles, keywords_include, keywords_exclude, required_skills,
    locations_allowed, remote_types, employment_types, experience_levels,
    salary_min_gbp, sponsorship_policy, application_mode, scoring_weights, is_active
  ) values (
    v_pref_id,
    v_profile_id,
    array['Backend Engineer','Software Engineer','Platform Engineer'],
    array[]::text[],
    array[]::text[],
    array['Java','Kotlin','Spring'],
    array['UK'],
    array['REMOTE','HYBRID'],
    array['FULL_TIME'],
    array[]::text[],
    60000,
    'SHOW_ALL',
    'ASSISTED',
    jsonb_build_object(
      'skill', 30, 'experience', 15, 'visa', 20, 'location', 10,
      'salary', 10, 'career', 10, 'difficulty', 5
    ),
    true
  );

  insert into companies (id, slug, name, careers_url, industry, size_bucket, hq_country)
  values
    (v_company1_id, 'example-fintech', 'Example Fintech Ltd',
     'https://boards.greenhouse.io/examplefintech', 'Fintech', 'MID', 'GB'),
    (v_company2_id, 'example-cloud', 'Example Cloud Co',
     'https://jobs.lever.co/examplecloud', 'Cloud Infrastructure', 'MID', 'GB');

  insert into job_sources (
    id, kind, org_identifier, company_id, display_name, capabilities, policy
  ) values
    (v_source1_id, 'GREENHOUSE', 'examplefintech', v_company1_id, 'Example Fintech (Greenhouse)',
     '{"discovery": true, "api": true, "automation": false}'::jsonb, 'DISCOVERY_ONLY'),
    (v_source2_id, 'LEVER', 'examplecloud', v_company2_id, 'Example Cloud Co (Lever)',
     '{"discovery": true, "api": true, "automation": false}'::jsonb, 'DISCOVERY_ONLY');

  insert into jobs (
    id, source_id, external_id, dedup_key, company_id, company_name_raw, title,
    location_raw, city, country, remote_type, employment_type, experience_level,
    salary_min, salary_max, salary_currency, salary_period, description_text,
    skills_extracted, application_url, canonical_url, posted_at, posted_date_source,
    content_hash, status
  ) values
    (
      gen_random_uuid(), v_source1_id, 'demo-001',
      md5('example-fintech|senior backend engineer|london'),
      v_company1_id, 'Example Fintech Ltd', 'Senior Backend Engineer',
      'London, UK', 'London', 'GB', 'HYBRID', 'FULL_TIME', 'SENIOR',
      70000, 85000, 'GBP', 'YEAR',
      'Seed job for Phase 1 development. Build payment rails on the JVM (Kotlin, Spring Boot), AWS EKS.',
      array['Kotlin','Spring Boot','AWS','Kubernetes'],
      'https://boards.greenhouse.io/examplefintech/jobs/1000001',
      'https://boards.greenhouse.io/examplefintech/jobs/1000001',
      now() - interval '3 days', 'EXPLICIT',
      md5('demo-001-v1'), 'DISCOVERED'
    ),
    (
      gen_random_uuid(), v_source2_id, 'demo-002',
      md5('example-cloud|platform engineer|remote'),
      v_company2_id, 'Example Cloud Co', 'Platform Engineer',
      'Remote (UK)', null, 'GB', 'REMOTE', 'FULL_TIME', 'MID',
      60000, 75000, 'GBP', 'YEAR',
      'Seed job for Phase 1 development. Own CI/CD and internal developer platform tooling.',
      array['Java','Terraform','Docker'],
      'https://jobs.lever.co/examplecloud/2000002',
      'https://jobs.lever.co/examplecloud/2000002',
      now() - interval '1 day', 'EXPLICIT',
      md5('demo-002-v1'), 'DISCOVERED'
    );

end
$$;
