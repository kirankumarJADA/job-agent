package com.personal.jobagent.discovery;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

@Service
public class LinkedInDiscoveryService {
    public record SearchRequest(String keywords,String location,String remoteType,int page,int pageSize){}
    public record SearchResult(String provider,String correlationId,List<JobDiscoveryService.IngestResult> ingested,String error){}
    private static final Pattern JOB_ID=Pattern.compile("/jobs/view/(\\d+)",Pattern.CASE_INSENSITIVE);
    private final Environment env; private final JobDiscoveryService ingestion; private final JobDeduplicationService dedup; private final HttpClient client;
    public LinkedInDiscoveryService(Environment env,JobDiscoveryService ingestion,JobDeduplicationService dedup){this.env=env;this.ingestion=ingestion;this.dedup=dedup;this.client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();}
    public SearchResult search(UUID sourceId,SearchRequest request){String correlation=UUID.randomUUID().toString();try{String url=buildUrl(request);HttpRequest http=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","PersonalJobAgent/1.0 (+permitted-public-discovery)").GET().build();HttpResponse<String> response=client.send(http,HttpResponse.BodyHandlers.ofString());if(response.statusCode()==401||response.statusCode()==403||response.statusCode()==999)return new SearchResult("linkedin",correlation,List.of(),"ACCESS_CONTROL_OR_AUTH_REQUIRED");if(response.statusCode()>=400)return new SearchResult("linkedin",correlation,List.of(),"HTTP_"+response.statusCode());ScraperProvider.ExtractedJob job=DiscoveryExtractionParser.fromHtml(response.body(),url,"linkedin",.60);if(job==null)return new SearchResult("linkedin",correlation,List.of(),"INCOMPLETE_PUBLIC_RESULT");Matcher id=JOB_ID.matcher(response.body());String external=id.find()?id.group(1):job.externalJobId();job=new ScraperProvider.ExtractedJob(job.title(),job.company(),job.location(),job.remoteType(),job.salary(),job.employmentType(),job.description(),job.skills(),job.applicationUrl(),job.sourceUrl(),job.postedAt(),external,job.contentHash(),job.confidence(),job.raw());var result=ingestion.ingestJob(new JobDiscoveryService.IngestJobCommand(sourceId,external==null?JobDeduplicationService.normalizeUrl(job.sourceUrl()):external,null,job.company(),job.title(),job.location(),null,null,job.remoteType(),job.employmentType(),null,null,null,null,job.description(),job.skills(),job.applicationUrl(),job.sourceUrl()));dedup.recordObservation(result.jobId(),"linkedin",job,job.confidence());return new SearchResult("linkedin",correlation,List.of(result),null);}catch(Exception e){return new SearchResult("linkedin",correlation,List.of(),e.getClass().getSimpleName());}}
    private String buildUrl(SearchRequest r){String base=env.getProperty("LINKEDIN_SEARCH_URL","https://www.linkedin.com/jobs/search/");String sep=base.contains("?")?"&":"?";StringBuilder q=new StringBuilder(base).append(sep).append("keywords=").append(enc(r.keywords())).append("&location=").append(enc(r.location())).append("&start=").append(Math.max(0,r.page())*Math.max(1,r.pageSize()));if(r.remoteType()!=null&&!r.remoteType().isBlank())q.append("&f_WT=").append(remoteCode(r.remoteType()));return q.toString();}
    private static String remoteCode(String type){return switch(type.toUpperCase(Locale.ROOT)){case "REMOTE"->"2";case "HYBRID"->"3";case "ONSITE","ON_SITE"->"1";default->"";};}
    private static String enc(String v){return URLEncoder.encode(v==null?"":v,StandardCharsets.UTF_8);}
}
