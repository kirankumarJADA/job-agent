package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.common.MaintenanceModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Retention for Robin-managed email (Phase 8.5): keeps as little as possible
 * for as short a time as possible.
 * <ul>
 *   <li>Encrypted message content is erased after the content retention
 *       (default two hours — a verification code is only ever used inside the
 *       verification window). Content is also erased at first use.</li>
 *   <li>Message metadata (sender, redacted subject, times) is deleted after the
 *       metadata retention (default 30 days).</li>
 *   <li>Accepted webhook tokens are deleted once no request carrying them could
 *       pass the timestamp check any more.</li>
 * </ul>
 * Runs every ten minutes; each pass is bounded so it never holds long locks.
 */
@Component
public class ManagedMailRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ManagedMailRetentionJob.class);
    private static final int BATCH = 1_000;

    private final JdbcTemplate db;
    private final ManagedMailSettings settings;
    private final MaintenanceModeService maintenanceMode;

    @Autowired
    public ManagedMailRetentionJob(JdbcTemplate db,
                                   ManagedMailSettings settings,
                                   @Autowired(required = false) MaintenanceModeService maintenanceMode) {
        this.db = db;
        this.settings = settings;
        this.maintenanceMode = maintenanceMode != null ? maintenanceMode : new MaintenanceModeService(false);
    }

    public ManagedMailRetentionJob(JdbcTemplate db, ManagedMailSettings settings) {
        this(db, settings, new MaintenanceModeService(false));
    }

    @Scheduled(fixedDelayString = "${app.managed-mail.retention-sweep-interval-ms:600000}",
            initialDelayString = "${app.managed-mail.retention-sweep-initial-delay-ms:60000}")
    public void sweep() {
        if (maintenanceMode.isMaintenanceMode()) {
            return;
        }
        try {
            Result result = purge(Instant.now());
            if (result.total() > 0) {
                log.info("Managed mail retention: erased content of {} message(s), deleted {} message record(s) "
                        + "and {} webhook token(s)", result.contentErased(), result.messagesDeleted(), result.tokensDeleted());
            }
        } catch (RuntimeException e) {
            log.warn("Managed mail retention sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    public record Result(int contentErased, int messagesDeleted, int tokensDeleted) {
        int total() {
            return contentErased + messagesDeleted + tokensDeleted;
        }
    }

    /** One bounded pass at {@code now}. */
    public Result purge(Instant now) {
        if (maintenanceMode.isMaintenanceMode()) {
            return new Result(0, 0, 0);
        }
        Timestamp tsNow = Timestamp.from(now);
        db.update("""
                update mailbox_verifications
                set status = 'EXPIRED'
                where id in (select id from mailbox_verifications
                             where status = 'RESERVED' and reserved_until <= ?
                             limit ?)
                """, tsNow, BATCH);
        db.update("""
                update managed_inbound_messages
                set reserved_for_application_id = null,
                    reserved_for_session_id = null,
                    reserved_at = null,
                    reservation_expires_at = null,
                    reservation_token_sha256 = null
                where id in (select id from managed_inbound_messages
                             where consumed_at is null and reserved_at is not null and reservation_expires_at <= ?
                             limit ?)
                """, tsNow, BATCH);
        int erased = db.update("""
                update managed_inbound_messages
                set content_encrypted = null,
                    content_purged_at = ?,
                    reserved_for_application_id = null,
                    reserved_for_session_id = null,
                    reserved_at = null,
                    reservation_expires_at = null,
                    reservation_token_sha256 = null
                where id in (select id from managed_inbound_messages
                             where content_encrypted is not null
                               and received_at < ?
                               and (reservation_expires_at is null or reservation_expires_at <= ?)
                             limit ?)
                """, tsNow, Timestamp.from(now.minus(settings.contentRetention())), tsNow, BATCH);
        int deleted = db.update("""
                delete from managed_inbound_messages
                where id in (select id from managed_inbound_messages where received_at < ? limit ?)
                """, Timestamp.from(now.minus(settings.metadataRetention())), BATCH);
        int tokens = db.update("""
                delete from managed_mail_webhook_tokens
                where token_sha256 in (select token_sha256 from managed_mail_webhook_tokens
                                       where received_at < ? limit ?)
                """, Timestamp.from(now.minus(settings.tokenRetention())), BATCH);
        return new Result(erased, deleted, tokens);
    }
}
