package com.personal.jobagent.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
class FirecrawlProvider extends ConfiguredSecondaryProvider {
    FirecrawlProvider(Environment e){super("firecrawl","FIRECRAWL_API_KEY",e);}
    @Override protected ProviderResponse request(DiscoveryRequest r)throws Exception{String base=environment.getProperty("FIRECRAWL_BASE_URL","https://api.firecrawl.dev/v2").replaceAll("/$","");String body=json.writeValueAsString(Map.of("url",r.sourceUrl(),"formats",List.of("markdown"),"onlyMainContent",true,"storeInCache",true));return post(base+"/scrape",body,Map.of("Authorization","Bearer "+credential(),"Content-Type","application/json"));}
}

@Component
class ApifyProvider extends ConfiguredSecondaryProvider {
    private final String actor; private final String task;
    ApifyProvider(Environment e){super("apify","APIFY_API_TOKEN",e);actor=e.getProperty("APIFY_ACTOR_ID","").trim();task=e.getProperty("APIFY_TASK_ID","").trim();}
    @Override public boolean enabled(){return super.enabled()&&(!actor.isBlank()||!task.isBlank());}
    @Override public ProviderState state(){return !super.enabled()?super.state():(actor.isBlank()&&task.isBlank()?ProviderState.UNCONFIGURED:super.state());}
    @Override protected ProviderResponse request(DiscoveryRequest r)throws Exception{String base=environment.getProperty("APIFY_BASE_URL","https://api.apify.com/v2").replaceAll("/$","");String endpoint=task.isBlank()?base+"/acts/"+SecondaryProviders.enc(actor)+"/run-sync-get-dataset-items":base+"/actor-tasks/"+SecondaryProviders.enc(task)+"/run-sync-get-dataset-items";String url=endpoint+"?token="+SecondaryProviders.enc(credential());String body=json.writeValueAsString(Map.of("url",r.sourceUrl()));return post(url,body,Map.of("Content-Type","application/json"));}
    @Override protected List<ExtractedJob> parse(ProviderResponse response,DiscoveryRequest request)throws Exception{JsonNode root=json.readTree(response.body());List<ExtractedJob> out=new ArrayList<>();if(root.isArray())for(JsonNode item:root){ExtractedJob j=DiscoveryExtractionParser.fromMarkdown(item.path("description").asText(item.path("text").asText("")),request.sourceUrl(),providerId(),.70,item);if(j!=null)out.add(j);}return out;}
}

@Component
class BrowserlessProvider extends ConfiguredSecondaryProvider {
    BrowserlessProvider(Environment e){super("browserless","BROWSERLESS_API_TOKEN",e);}
    @Override protected ProviderResponse request(DiscoveryRequest r)throws Exception{String endpoint=environment.getProperty("BROWSERLESS_BASE_URL","https://production-sfo.browserless.io/content");String url=endpoint+"?token="+SecondaryProviders.enc(credential());String body=json.writeValueAsString(Map.of("url",r.sourceUrl()));return post(url,body,Map.of("Content-Type","application/json","Cache-Control","no-cache"));}
}

@Component
class ScraperApiProvider extends ConfiguredSecondaryProvider {
    ScraperApiProvider(Environment e){super("scraperapi","SCRAPERAPI_API_KEY",e);}
    @Override protected ProviderResponse request(DiscoveryRequest r)throws Exception{String endpoint=environment.getProperty("SCRAPERAPI_BASE_URL","https://api.scraperapi.com/");String url=endpoint+"?api_key="+SecondaryProviders.enc(credential())+"&url="+SecondaryProviders.enc(r.sourceUrl())+"&render=true&follow_redirect=true";return get(url,Map.of());}
}

@Component
class ScrapingBeeProvider extends ConfiguredSecondaryProvider {
    ScrapingBeeProvider(Environment e){super("scrapingbee","SCRAPINGBEE_API_KEY",e);}
    @Override protected ProviderResponse request(DiscoveryRequest r)throws Exception{String endpoint=environment.getProperty("SCRAPINGBEE_BASE_URL","https://app.scrapingbee.com/api/v1");String url=endpoint+"?api_key="+SecondaryProviders.enc(credential())+"&url="+SecondaryProviders.enc(r.sourceUrl())+"&render_js=true&return_page_source=true";return get(url,Map.of());}
}

final class SecondaryProviders { private SecondaryProviders(){} static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);} }
