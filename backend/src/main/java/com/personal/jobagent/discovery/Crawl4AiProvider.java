package com.personal.jobagent.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;

@Component
public class Crawl4AiProvider implements ScraperProvider {
    private final ObjectMapper json; private final HttpClient client=HttpClient.newHttpClient(); private final String baseUrl; private final String token;
    public Crawl4AiProvider(ObjectMapper json,Environment env){this.json=json;this.baseUrl=env.getProperty("CRAWL4AI_BASE_URL","").trim();this.token=env.getProperty("CRAWL4AI_API_TOKEN","").trim();}
    @Override public String providerId(){return "crawl4ai";}
    @Override public ProviderRole role(){return ProviderRole.PRIMARY;}
    @Override public boolean enabled(){return !baseUrl.isBlank();}
    @Override public ProviderState state(){return enabled()?ProviderState.AVAILABLE:ProviderState.UNCONFIGURED;}
    @Override public ExtractionResult extract(DiscoveryRequest request){Instant started=Instant.now();if(!enabled())return new ExtractionResult(providerId(),request.correlationId(),started,Instant.now(),List.of(),0,"UNCONFIGURED",0);try{String body=json.writeValueAsString(Map.of("urls",List.of(request.sourceUrl()),"word_count_threshold",10));HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/$","")+"/crawl")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));if(!token.isBlank()){b.header("Authorization","Bearer "+token);b.header("X-API-Key",token);}HttpResponse<String> response=client.send(b.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()>=400)return new ExtractionResult(providerId(),request.correlationId(),started,Instant.now(),List.of(),0,"HTTP_"+response.statusCode(),0);JsonNode root=json.readTree(response.body());List<ExtractedJob> jobs=new ArrayList<>();JsonNode items=root.isArray()?root:(root.has("results")?root.path("results"):root.has("data")?root.path("data"):root);if(items.isArray())for(JsonNode item:items){String md=item.path("markdown").asText(item.path("content").asText(""));ExtractedJob j=DiscoveryExtractionParser.fromMarkdown(md,request.sourceUrl(),providerId(),.80,item);if(j!=null)jobs.add(j);}else{String md=items.path("markdown").asText(items.path("content").asText(""));ExtractedJob j=DiscoveryExtractionParser.fromMarkdown(md,request.sourceUrl(),providerId(),.80,items);if(j!=null)jobs.add(j);}if(jobs.isEmpty())return new ExtractionResult(providerId(),request.correlationId(),started,Instant.now(),List.of(),0,"INCOMPLETE_CONTENT",0);return new ExtractionResult(providerId(),request.correlationId(),started,Instant.now(),jobs,.80,null,0);}catch(Exception e){return new ExtractionResult(providerId(),request.correlationId(),started,Instant.now(),List.of(),0,e.getClass().getSimpleName(),0);}}
}
