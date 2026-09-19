package com.personal.jobagent.discovery;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OurOwnWebScraper implements ScraperProvider {
    private static final Pattern LOC = Pattern.compile("<loc>\\s*(.*?)\\s*</loc>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NEXT = Pattern.compile("<link[^>]+rel=[\\\"']next[\\\"'][^>]+href=[\\\"'](.*?)[\\\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private final HttpClient client;
    private final int retries;
    private final int maxPages;
    private final Duration timeout;
    private final boolean robotsRequired;

    public OurOwnWebScraper(Environment environment) {
        this.timeout = Duration.ofMillis(environment.getProperty("DISCOVERY_HTTP_TIMEOUT_MS", Integer.class, 10000));
        this.retries = Math.max(0, environment.getProperty("DISCOVERY_HTTP_RETRIES", Integer.class, 2));
        this.maxPages = Math.max(1, environment.getProperty("DISCOVERY_MAX_PAGES", Integer.class, 5));
        this.robotsRequired = environment.getProperty("DISCOVERY_ROBOTS_REQUIRED", Boolean.class, true);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Override public String providerId() { return "our-own-scraper"; }
    @Override public ProviderRole role() { return ProviderRole.PRIMARY; }
    @Override public boolean enabled() { return true; }
    @Override public ProviderState state() { return ProviderState.AVAILABLE; }

    @Override public ExtractionResult extract(DiscoveryRequest request) {
        Instant started = Instant.now(); int attempts = 0;
        try {
            URI requested = URI.create(request.sourceUrl());
            if (!isAllowedByRobots(requested)) return failed(request, started, "ROBOTS_DISALLOWED", attempts);
            List<ExtractedJob> jobs = new ArrayList<>(); Set<String> visited = new LinkedHashSet<>();
            List<String> targets = new ArrayList<>();
            if (requested.getPath() != null && requested.getPath().toLowerCase(Locale.ROOT).endsWith(".xml")) targets.add(requested.toString());
            else targets.add(requested.toString());
            for (int index=0; index<targets.size() && visited.size()<maxPages; index++) {
                String target=targets.get(index); if(!visited.add(JobDeduplicationService.normalizeUrl(target)))continue;
                HttpResponse<String> response = null; String error=null;
                for(int attempt=0; attempt<=retries; attempt++){attempts++; try{response=fetch(URI.create(target),request.headers()); if(response.statusCode()<500)break; error="HTTP_"+response.statusCode();}catch(Exception e){error=e.getClass().getSimpleName(); if(attempt<retries)Thread.sleep(Math.min(1000L*(attempt+1),3000));}}
                if(response==null){return failed(request,started,error==null?"REQUEST_FAILED":error,attempts);}
                if(response.statusCode()>=400)return failed(request,started,"HTTP_"+response.statusCode(),attempts);
                String body=response.body();
                if(target.toLowerCase(Locale.ROOT).endsWith(".xml")||body.trim().startsWith("<?xml")||body.contains("<urlset")){for(String loc:matches(LOC,body))if(targets.size()<maxPages)targets.add(resolve(target,loc));continue;}
                if(!isJobLike(body, target))continue;
                ExtractedJob job=DiscoveryExtractionParser.fromHtml(body,target,providerId(),hasStructuredJobPosting(body) ? .85 : .70);
                if(job!=null)jobs.add(job);
                Matcher next=NEXT.matcher(body); if(next.find()&&targets.size()<maxPages)targets.add(resolve(target,next.group(1)));
                for(String link:DiscoveryExtractionParser.links(body)) if(isLikelyJobLink(link)&&targets.size()<maxPages)targets.add(resolve(target,link));
            }
            if(jobs.isEmpty())return failed(request,started,"INCOMPLETE_JOB_EXTRACTION",attempts);
            return new ExtractionResult(providerId(),request.correlationId(),started,Instant.now(),jobs,
                    jobs.stream().mapToDouble(ExtractedJob::confidence).average().orElse(0),null,Math.max(0,attempts-1));
        } catch(Exception e){return failed(request,started,e.getClass().getSimpleName(),attempts);}
    }

    private HttpResponse<String> fetch(URI uri, Map<String,String> headers) throws Exception { HttpRequest.Builder b=HttpRequest.newBuilder(uri).timeout(timeout).GET().header("User-Agent","PersonalJobAgent/1.0 (+permitted-discovery)"); headers.forEach(b::header); return client.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    private boolean isAllowedByRobots(URI uri){if(!robotsRequired)return true;try{URI robots=new URI(uri.getScheme(),uri.getAuthority(),"/robots.txt",null,null);HttpResponse<String> r=fetch(robots,Map.of());if(r.statusCode()==404)return true;if(r.statusCode()>=400)return false;return !r.body().lines().anyMatch(line->{String s=line.trim().toLowerCase(Locale.ROOT);return s.startsWith("disallow: /")&&!s.equals("disallow: ");});}catch(Exception e){return false;}}
    private static boolean isJobLike(String html,String url){String s=(html+" "+url).toLowerCase(Locale.ROOT);return html.contains("JobPosting")||s.matches(".*(job|career|position|opening|vacanc|responsibilit|qualification|requirement).*?");}
    private static boolean hasStructuredJobPosting(String html){return html.toLowerCase(Locale.ROOT).contains("jobposting");}
    private static boolean isLikelyJobLink(String link){String s=link.toLowerCase(Locale.ROOT);return s.contains("job")||s.contains("career")||s.contains("position")||s.contains("opening");}
    private static String resolve(String base,String link){try{return URI.create(base).resolve(link).toString();}catch(Exception e){return link;}}
    private static List<String> matches(Pattern p,String text){List<String> out=new ArrayList<>();Matcher m=p.matcher(text);while(m.find())out.add(m.group(1).trim());return out;}
    private ExtractionResult failed(DiscoveryRequest r,Instant started,String error,int attempts){return new ExtractionResult(providerId(),r.correlationId(),started,Instant.now(),List.of(),0,error,attempts);}
}
