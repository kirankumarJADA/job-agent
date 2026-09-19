package com.personal.jobagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@Service
public class NimModelRegistry {
    private final ObjectMapper mapper; private final JdbcTemplate db; private final String baseUrl; private final String apiKey;
    public NimModelRegistry(ObjectMapper mapper, JdbcTemplate db, @Value("${NIM_BASE_URL:https://integrate.api.nvidia.com}") String baseUrl, @Value("${NIM_API_KEY:}") String apiKey){this.mapper=mapper;this.db=db;this.baseUrl=(baseUrl==null?"https://integrate.api.nvidia.com":baseUrl).replaceAll("/+$","");this.apiKey=apiKey;}
    @EventListener(ApplicationReadyEvent.class) public void refreshOnReady(){if(configured())try{refresh();}catch(RuntimeException ignored){}}
    public boolean configured(){return apiKey!=null&&!apiKey.isBlank();}
    public List<ModelDescriptor> refresh(){if(!configured())return List.of();try{HttpRequest req=HttpRequest.newBuilder().uri(URI.create(baseUrl+"/v1/models")).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+apiKey).GET().build();HttpResponse<String> r=HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.ofString());if(r.statusCode()/100!=2)throw new LlmProviderException("NVIDIA catalogue returned HTTP "+r.statusCode());JsonNode root=mapper.readTree(r.body()),data=root.path("data");if(!data.isArray())data=root.path("models");List<ModelDescriptor> out=new ArrayList<>();if(data.isArray())for(JsonNode n:data){String id=n.path("id").asText(n.path("model").asText());if(!id.isBlank())out.add(parse(n,id));}persist(out);return out;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new LlmProviderException("NVIDIA catalogue interrupted",e);}catch(Exception e){if(e instanceof LlmProviderException l)throw l;throw new LlmProviderException("NVIDIA catalogue failed: "+e.getMessage(),e);}}
    public Set<String> eligibleModelIds(){try{return new LinkedHashSet<>(db.query("select model_key from llm_models where provider_id='nim' and enabled=true and coalesce((notes->>'free_endpoint')::boolean,false)=true and coalesce((notes->>'deprecated')::boolean,false)=false and coalesce(notes->>'availability','UNKNOWN')='AVAILABLE'",(rs,n)->rs.getString(1)));}catch(Exception e){return Set.of();}}
    public List<Map<String,Object>> registryRows(){return db.queryForList("select provider_id,model_key,display_name,context_window,capabilities,enabled,notes from llm_models where provider_id='nim' order by model_key");}
    private ModelDescriptor parse(JsonNode n,String id){JsonNode meta=n.path("metadata");Set<String> caps=new LinkedHashSet<>();if(n.path("capabilities").isArray())n.path("capabilities").forEach(x->caps.add(x.asText()));String a=n.path("availability").asText(n.path("status").asText("AVAILABLE"));return new ModelDescriptor("nim",id,n.path("display_name").asText(id),caps,false,false,false,false,n.path("context_window").isInt()?n.path("context_window").asInt():null,null,a.toUpperCase(),n.path("free_endpoint").asBoolean(meta.path("free_endpoint").asBoolean(false)),n.path("deprecated").asBoolean(meta.path("deprecated").asBoolean(false)),Instant.now());}
    private void persist(List<ModelDescriptor> ms){for(ModelDescriptor m:ms)try{String notes=mapper.writeValueAsString(Map.of("capabilities",m.capabilities(),"availability",m.availability(),"free_endpoint",m.freeEndpoint(),"deprecated",m.deprecated(),"last_seen_at",m.lastSeenAt().toString()));db.update("insert into llm_models(id,provider_id,model_key,display_name,context_window,enabled,notes) values(?,?,?,?,?,?,?::jsonb) on conflict(provider_id,model_key) do update set display_name=excluded.display_name,context_window=excluded.context_window,enabled=excluded.enabled,notes=excluded.notes",UuidV7.generate(),"nim",m.modelId(),m.displayName(),m.contextWindow(),m.eligibleForAutomaticUse(),notes);}catch(Exception e){throw new IllegalStateException(e);}}
}
