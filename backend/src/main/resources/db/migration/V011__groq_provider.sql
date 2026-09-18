alter table llm_providers drop constraint if exists llm_providers_kind_check;
alter table llm_providers add constraint llm_providers_kind_check check (kind in ('NVIDIA_NIM','GEMINI','OPENAI','OLLAMA','GROQ','FUTURE'));
insert into llm_providers(id,kind,base_url,api_key_env_var,enabled,config)
select 'groq','GROQ','https://api.groq.com/openai','GROQ_API_KEY',true,'{}'::jsonb
where not exists(select 1 from llm_providers where id='groq');
insert into llm_models(id,provider_id,model_key,display_name,enabled,notes)
select gen_random_uuid(),'groq','llama-3.3-70b-versatile','Groq Llama 3.3 70B',true,'{}'::jsonb
where not exists(select 1 from llm_models where provider_id='groq' and model_key='llama-3.3-70b-versatile');
