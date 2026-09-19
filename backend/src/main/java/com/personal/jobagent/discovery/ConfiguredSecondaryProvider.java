package com.personal.jobagent.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public abstract class ConfiguredSecondaryProvider implements ScraperProvider {
    protected record ProviderResponse(int status, String body, Map<String,String> headers) {}
    protected final Environment environment;
    protected final ObjectMapper json = new ObjectMapper();
    private final String id;
    private final String credential;
    private final AtomicReference<ProviderState> currentState = new AtomicReference<>();
    private final HttpClient client;
    private final Duration timeout;
    private final int retries;

    protected ConfiguredSecondaryProvider(String id, String envKey, Environment environment) {
        this.environment=environment; this.id=id; this.credential=environment.getProperty(envKey, "").trim();
        this.timeout=Duration.ofMillis(environment.getProperty("DISCOVERY_HTTP_TIMEOUT_MS",Integer.class,15000));
        this.retries=Math.max(0,environment.getProperty("DISCOVERY_HTTP_RETRIES",Integer.class,2));
        this.client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
        this.currentState.set(credential.isBlank()?ProviderState.UNCONFIGURED:ProviderState.AVAILABLE);
    }
    @Override public String providerId(){return id;}
    @Override public ProviderRole role(){return ProviderRole.SECONDARY;}
    @Override public boolean enabled(){return !credential.isBlank() && state()!=ProviderState.DISABLED && state()!=ProviderState.EXHAUSTED;}
    @Override public ProviderState state(){return currentState.get();}
    protected String credential(){return credential;}

    @Override public ExtractionResult extract(DiscoveryRequest request){
        Instant started=Instant.now(); if(!enabled())return result(request,started,List.of(),0,state()==ProviderState.UNCONFIGURED?"UNCONFIGURED":state()==ProviderState.EXHAUSTED?"QUOTA_EXHAUSTED":"DISABLED",0);
        int attempts=0; String lastError="REQUEST_FAILED";
        for(int attempt=0;attempt<=retries;attempt++){attempts++;try{ProviderResponse response=request(request); if(response.status()==429){currentState.set(ProviderState.EXHAUSTED);return result(request,started,List.of(),0,"QUOTA_EXHAUSTED",attempts);} if(response.status()>=500){currentState.set(ProviderState.DEGRADED);lastError="HTTP_"+response.status();} else if(response.status()>=400){lastError="HTTP_"+response.status();} else {List<ExtractedJob> jobs=parse(response,request);if(jobs.isEmpty()){lastError="INCOMPLETE_EXTRACTION";currentState.set(ProviderState.DEGRADED);}else{currentState.set(ProviderState.AVAILABLE);return result(request,started,jobs,jobs.stream().mapToDouble(ExtractedJob::confidence).average().orElse(0),null,attempts);}}}catch(Exception e){lastError=e.getClass().getSimpleName();currentState.set(ProviderState.DEGRADED);} if(attempt<retries)try{Thread.sleep(Math.min(500L*(attempt+1),2000));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
        return result(request,started,List.of(),0,lastError,attempts);
    }
    protected abstract ProviderResponse request(DiscoveryRequest request) throws Exception;
    protected List<ExtractedJob> parse(ProviderResponse response,DiscoveryRequest request) throws Exception {
        String body=response.body(); String contentType=response.headers().getOrDefault("content-type","").toLowerCase(Locale.ROOT);
        if((contentType.contains("html")||body.trim().startsWith("<"))&&!body.trim().startsWith("{")){ExtractedJob job=DiscoveryExtractionParser.fromHtml(body,request.sourceUrl(),providerId(),.75);return job==null?List.of():List.of(job);}
        JsonNode root=json.readTree(body); JsonNode data=root.has("data")?root.path("data"):root; String markdown=data.path("markdown").asText(data.path("content").asText(""));
        ExtractedJob job=DiscoveryExtractionParser.fromMarkdown(markdown,request.sourceUrl(),providerId(),.75,data);return job==null?List.of():List.of(job);
    }
    protected ProviderResponse get(String url,Map<String,String> headers) throws Exception{return send(HttpRequest.newBuilder(URI.create(url)).GET(),headers);}
    protected ProviderResponse post(String url,String body,Map<String,String> headers) throws Exception{return send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body)),headers);}
    private ProviderResponse send(HttpRequest.Builder builder,Map<String,String> headers) throws Exception{builder.timeout(timeout).header("User-Agent","PersonalJobAgent/1.0 (+permitted-discovery)");headers.forEach(builder::header);HttpResponse<String> r=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());Map<String,String> h=new HashMap<>();r.headers().map().forEach((k,v)->{if(!v.isEmpty())h.put(k.toLowerCase(Locale.ROOT),v.get(0));});return new ProviderResponse(r.statusCode(),r.body(),h);}
    protected ExtractionResult result(DiscoveryRequest r,Instant started,List<ExtractedJob> jobs,double confidence,String error,int attempts){return new ExtractionResult(providerId(),r.correlationId(),started,Instant.now(),jobs,confidence,error,Math.max(0,attempts-1));}
}
