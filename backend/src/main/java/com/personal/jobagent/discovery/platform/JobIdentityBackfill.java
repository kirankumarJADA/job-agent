package com.personal.jobagent.discovery.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gives jobs that predate V037 their URL and platform identity keys, using
 * exactly the normalisation live ingestion uses ({@link JobIdentity}). Runs
 * once at startup, in bounded batches, and is idempotent: only jobs without
 * any ATS/URL key are visited and inserts never overwrite (first writer wins,
 * so two old rows that happen to share a URL are NOT merged retroactively).
 * A failure is logged and never blocks startup.
 */
@Component
public class JobIdentityBackfill {

    private static final Logger log = LoggerFactory.getLogger(JobIdentityBackfill.class);
    private static final int BATCH = 500;

    private final JdbcTemplate db;
    private final boolean enabled;

    public JobIdentityBackfill(JdbcTemplate db, @Value("${app.discovery.identity-backfill-enabled:true}") boolean enabled) {
        this.db = db;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) return;
        try {
            int total = backfill(20);
            if (total > 0) log.info("Identity backfill gave {} job(s) URL/platform keys", total);
        } catch (RuntimeException e) {
            log.warn("Identity backfill skipped: {}", e.getClass().getSimpleName());
        }
    }

    /** @return number of jobs processed (at most {@code maxBatches * 500}) */
    public int backfill(int maxBatches) {
        int processed = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<Map<String, Object>> rows = db.queryForList("""
                    select j.id, j.source_id, j.external_id, j.canonical_url, j.application_url, j.company_name_raw
                    from jobs j
                    where not exists (select 1 from job_identity_keys k
                                      where k.job_id = j.id and k.key_type in ('ATS','URL'))
                      and (j.canonical_url is not null or j.application_url is not null)
                      and not exists (select 1 from job_identity_backfill_skips s where s.job_id = j.id)
                    order by j.first_seen_at, j.id
                    limit ?
                    """, BATCH);
            if (rows.isEmpty()) break;
            for (Map<String, Object> r : rows) {
                UUID jobId = (UUID) r.get("id");
                String application = (String) r.get("application_url");
                List<JobIdentity.Key> keys = JobIdentity.keys((UUID) r.get("source_id"), (String) r.get("external_id"),
                        (String) r.get("canonical_url"), JobIdentity.isAggregatorUrl(application) ? null : application,
                        (String) r.get("company_name_raw"), null);
                int added = 0;
                for (JobIdentity.Key key : keys) {
                    if (key.type() == JobIdentity.KeyType.SRC) continue; // V037 already wrote these
                    added += db.update("insert into job_identity_keys(key, job_id, key_type, source_id) values (?, ?, ?, ?) "
                            + "on conflict (key) do nothing", key.asString(), jobId, key.type().name(), r.get("source_id"));
                }
                if (added == 0) {
                    // Every key already belongs to an older job (or none could be derived):
                    // remember the visit so the loop always terminates.
                    db.update("insert into job_identity_backfill_skips(job_id) values (?) on conflict do nothing", jobId);
                }
                processed++;
            }
        }
        return processed;
    }
}
