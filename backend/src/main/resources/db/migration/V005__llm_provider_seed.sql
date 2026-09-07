-- V005__llm_provider_seed.sql
-- Seeds llm_providers/llm_models so GET /models has something to show.
-- nim/gemini model_key values are PLACEHOLDERS — per PHASE1-BLUEPRINT.md,
-- real identifiers are an open item pending real API credentials (not
-- available during this build pass). Do not treat 'nim-placeholder-model'/
-- 'gemini-placeholder-model' as real, callable model identifiers — replace
-- them via a future migration once real values are confirmed. 'simulated'
-- is real and always callable (SimulatedProvider, P1-e).

insert into llm_providers (id, kind, base_url, api_key_env_var, enabled, config)
values
  ('nim', 'NVIDIA_NIM', null, 'NIM_API_KEY', true, '{}'::jsonb),
  ('gemini', 'GEMINI', 'https://generativelanguage.googleapis.com', 'GEMINI_API_KEY', true, '{}'::jsonb),
  ('simulated', 'FUTURE', null, 'NONE', true, '{"note":"always available, canned responses, Phase 1 fallback/demo only"}'::jsonb);

insert into llm_models (id, provider_id, model_key, display_name, context_window, enabled, notes)
values
  (gen_random_uuid(), 'nim', 'nim-placeholder-model', 'NIM (placeholder — real model TBD)', null, true,
   '{"placeholder": true}'::jsonb),
  (gen_random_uuid(), 'gemini', 'gemini-placeholder-model', 'Gemini (placeholder — real model TBD)', null, true,
   '{"placeholder": true}'::jsonb),
  (gen_random_uuid(), 'simulated', 'simulated-v1', 'Simulated (Phase 1 demo/fallback)', null, true,
   '{"placeholder": false}'::jsonb);
