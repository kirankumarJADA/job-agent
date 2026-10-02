package com.personal.jobagent.discovery;

import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;

@Service
public class JobDeduplicationService {
    private final JdbcTemplate db;
    public JobDeduplicationService(JdbcTemplate db){this.db=db;}
    public void recordObservation(UUID jobId,ScraperProvider provider,ScraperProvider.ExtractedJob job,double confidence){recordObservation(jobId,provider.providerId(),job,confidence);}
    public void recordObservation(UUID jobId,String providerId,ScraperProvider.ExtractedJob job,double confidence){
        String source=normalizeUrl(job.sourceUrl()!=null?job.sourceUrl():job.applicationUrl());String external=job.externalJobId()==null?"":job.externalJobId();
        db.update("insert into job_source_observations(id,job_id,source_type,provider,source_url,external_job_id,content_hash,extraction_hash,extraction_confidence,last_seen_at) values(?,?,?,?,?,?,?,?,?,now()) on conflict(provider,source_url,external_job_id) do update set job_id=excluded.job_id,content_hash=excluded.content_hash,extraction_hash=excluded.extraction_hash,extraction_confidence=excluded.extraction_confidence,last_seen_at=now(),source_status='SEEN'",UuidV7.generate(),jobId,"WEB",providerId,source,external,job.contentHash(),job.contentHash(),confidence);
    }
    public static String normalizeUrl(String value){if(value==null||value.isBlank())return "";try{URI u=URI.create(value.trim());return (u.getScheme()==null?"":u.getScheme().toLowerCase(Locale.ROOT)+"://")+(u.getHost()==null?"":u.getHost().toLowerCase(Locale.ROOT))+(u.getPath()==null?"":u.getPath().replaceAll("/+$",""));}catch(Exception e){return value.trim().replaceAll("[?].*$","").replaceAll("/+$","").toLowerCase(Locale.ROOT);}}
    public static String fingerprint(ScraperProvider.ExtractedJob j){return JobDiscoveryService.computeDedupKey(j.company(),j.title(),j.location());}
}
