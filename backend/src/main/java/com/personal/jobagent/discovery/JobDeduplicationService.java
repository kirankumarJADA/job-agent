package com.personal.jobagent.discovery;

import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.*;

@Service
public class JobDeduplicationService {
    private final JdbcTemplate db;
    public JobDeduplicationService(JdbcTemplate db){this.db=db;}
    public Optional<UUID> resolveExisting(ScraperProvider.ExtractedJob j){
        String external=j.externalJobId();
        if(external!=null&&!external.isBlank()){List<UUID>x=db.query("select job_id from job_source_observations where external_job_id=? order by last_seen_at desc limit 1",(r,n)->(UUID)r.getObject(1),external);if(!x.isEmpty())return Optional.of(x.get(0));}
        String u=normalizeUrl(j.applicationUrl()!=null?j.applicationUrl():j.sourceUrl());
        if(!u.isBlank()){List<UUID>x=db.query("select job_id from job_source_observations where source_url=? order by last_seen_at desc limit 1",(r,n)->(UUID)r.getObject(1),u);if(!x.isEmpty())return Optional.of(x.get(0));}
        List<UUID>x=db.query("select id from jobs where dedup_key=? or content_hash=? limit 1",(r,n)->(UUID)r.getObject(1),fingerprint(j),j.contentHash());return x.stream().findFirst();
    }
    public void recordObservation(UUID jobId,ScraperProvider provider,ScraperProvider.ExtractedJob job,double confidence){recordObservation(jobId,provider.providerId(),job,confidence);}
    public void recordObservation(UUID jobId,String providerId,ScraperProvider.ExtractedJob job,double confidence){
        String source=normalizeUrl(job.sourceUrl()!=null?job.sourceUrl():job.applicationUrl());String external=job.externalJobId()==null?"":job.externalJobId();
        db.update("insert into job_source_observations(id,job_id,source_type,provider,source_url,external_job_id,content_hash,extraction_hash,extraction_confidence,last_seen_at) values(?,?,?,?,?,?,?,?,?,now()) on conflict(provider,source_url,external_job_id) do update set job_id=excluded.job_id,content_hash=excluded.content_hash,extraction_hash=excluded.extraction_hash,extraction_confidence=excluded.extraction_confidence,last_seen_at=now(),source_status='SEEN'",UuidV7.generate(),jobId,"WEB",providerId,source,external,job.contentHash(),job.contentHash(),confidence);
    }
    public static String normalizeUrl(String value){if(value==null||value.isBlank())return "";try{URI u=URI.create(value.trim());return (u.getScheme()==null?"":u.getScheme().toLowerCase(Locale.ROOT)+"://")+(u.getHost()==null?"":u.getHost().toLowerCase(Locale.ROOT))+(u.getPath()==null?"":u.getPath().replaceAll("/+$",""));}catch(Exception e){return value.trim().replaceAll("[?].*$","").replaceAll("/+$","").toLowerCase(Locale.ROOT);}}
    public static String fingerprint(ScraperProvider.ExtractedJob j){return JobDiscoveryService.computeDedupKey(j.company(),j.title(),j.location());}
}
