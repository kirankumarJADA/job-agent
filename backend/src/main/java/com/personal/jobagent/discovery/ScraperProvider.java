package com.personal.jobagent.discovery;
import java.time.Instant; import java.util.*;
public interface ScraperProvider {
 String providerId(); ProviderRole role(); boolean enabled(); ProviderState state(); ExtractionResult extract(DiscoveryRequest request);
 enum ProviderRole { PRIMARY, SECONDARY } enum ProviderState { AVAILABLE, DEGRADED, EXHAUSTED, DISABLED, UNCONFIGURED }
 record DiscoveryRequest(String sourceUrl,String sourceType,String correlationId,Map<String,String> headers){public DiscoveryRequest{headers=headers==null?Map.of():Map.copyOf(headers);}}
 record ExtractedJob(String title,String company,String location,String remoteType,String salary,String employmentType,String description,List<String> skills,String applicationUrl,String sourceUrl,Instant postedAt,String externalJobId,String contentHash,double confidence,Map<String,Object> raw){public ExtractedJob{skills=skills==null?List.of():List.copyOf(skills);raw=raw==null?Map.of():Map.copyOf(raw);}}
 record ExtractionResult(String provider,String correlationId,Instant startedAt,Instant completedAt,List<ExtractedJob> jobs,double confidence,String error,int retryCount){public boolean successful(){return error==null&&!jobs.isEmpty();} public ExtractionResult{jobs=jobs==null?List.of():List.copyOf(jobs);}}
}
