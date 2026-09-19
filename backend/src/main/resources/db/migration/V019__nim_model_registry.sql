delete from routing_policies where primary_model_id in (select id from llm_models where model_key in ('nim-placeholder-model','gemini-placeholder-model','llama-3.3-70b-versatile'));
delete from llm_models where model_key in ('nim-placeholder-model','gemini-placeholder-model','llama-3.3-70b-versatile');
update llm_providers set base_url='https://integrate.api.nvidia.com', enabled=true, config=jsonb_set(config,'{catalogue_path}','"/v1/models"') where id='nim';
insert into llm_models(id,provider_id,model_key,display_name,enabled,notes)
select gen_random_uuid(),'nim',v.model_key,v.display_name,false,'{"availability":"UNKNOWN","free_endpoint":false,"deprecated":false,"source":"NVIDIA_CATALOGUE"}'::jsonb
from (values
('nvidia/nemotron-3.5-lightning-30b-a3b','NVIDIA Nemotron 3.5 Lightning 30B A3B'),
('z-ai/glm-5-3','Z.ai GLM 5 3'),('z-ai/glm-5-3-flash','Z.ai GLM 5 3 Flash'),
('moonshotai/kimi-k3','MoonshotAI Kimi K3'),('qwen/qwen3-next-80b-a3b-thinking','Qwen 3 Next 80B A3B Thinking'),
('qwen/qwen3-next-80b-a3b-instruct','Qwen 3 Next 80B A3B Instruct'),('openai/gpt-oss-120b','OpenAI GPT OSS 120B'),('openai/gpt-oss-20b','OpenAI GPT OSS 20B')) v(model_key,display_name)
where not exists(select 1 from llm_models m where m.provider_id='nim' and m.model_key=v.model_key);
