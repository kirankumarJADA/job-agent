package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.common.SchedulerDatabaseGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired(required = false)
    private SchedulerDatabaseGuard databaseGuard;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

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
        if (maintenanceMode.isMaintenanceMode() || (databaseGuard != null && !databaseGuard.isReady())) {
            return;
        }
        try {
            // Stage 3I Phase 6: leases are written with the database clock (MailboxVerificationController#dbNow),
            // so expiry is judged by the same clock; an application server running ahead of the database
            // must never make an active lease look expired.
            Result result = purge(databaseNow());
            if (result.total() > 0) {
                log.info("Managed mail retention: erased content of {} message(s), deleted {} message record(s) "
                        + "and {} webhook token(s)", result.contentErased(), result.messagesDeleted(), result.tokensDeleted());
            }
        } catch (RuntimeException e) {
            if (databaseGuard != null && !databaseGuard.isReady()) {
                log.debug("Skipping managed mail retention error during shutdown: {}", e.getClass().getSimpleName());
                return;
            }
            log.warn("Managed mail retention sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    /** The database's current time, the clock every lease expiry is written with. */
    Instant databaseNow() {
        Timestamp ts = db.queryForObject("select now()", Timestamp.class);
        if (ts == null) throw new IllegalStateException("database clock unavailable");
        return ts.toInstant();
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
        if (databaseGuard != null && !databaseGuard.isReady()) {
            return new Result(0, 0, 0);
        }
        if (transactionManager != null) {
            Result result = new TransactionTemplate(transactionManager).execute(status -> executePurge(now));
            return result != null ? result : new Result(0, 0, 0);
        }
        return executePurge(now);
    }

    private Result executePurge(Instant now) {
        Timestamp tsNow = Timestamp.from(now);
        Timestamp contentCutoff = Timestamp.from(now.minus(settings.contentRetention()));
        Timestamp metadataCutoff = Timestamp.from(now.minus(settings.metadataRetention()));
        Timestamp tokenCutoff = Timestamp.from(now.minus(settings.tokenRetention()));

        db.update("""
                with target as (
                    select id from mailbox_verifications
                    where status = 'RESERVED' and reserved_until <= ?
                    order by reserved_until asc
                    limit ?
                    for update skip locked
                )
                update mailbox_verifications mv
                set status = 'EXPIRED',
                    reserved_until = null
                from target t
                where mv.id = t.id
                  and mv.status = 'RESERVED'
                  and mv.reserved_until <= ?
                """, tsNow, BATCH, tsNow);

        db.update("""
                with target as (
                    select id from managed_inbound_messages
                    where consumed_at is null and reserved_at is not null and reservation_expires_at <= ?
                    order by reservation_expires_at asc
                    limit ?
                    for update skip locked
                )
                update managed_inbound_messages m
                set reserved_for_application_id = null,
                    reserved_for_session_id = null,
                    reserved_at = null,
                    reservation_expires_at = null,
                    reservation_token_sha256 = null
                from target t
                where m.id = t.id
                  and m.consumed_at is null
                  and m.reserved_at is not null
                  and m.reservation_expires_at <= ?
                """, tsNow, BATCH, tsNow);

        int erased = db.update("""
                with target as (
                    select m.id from managed_inbound_messages m
                    where m.content_encrypted is not null
                      and m.received_at < ?
                      and (m.reservation_expires_at is null or m.reservation_expires_at <= ?)
                      and not exists (
                          select 1 from mailbox_verifications mv
                          where mv.source_managed_message_id = m.id
                            and mv.status = 'RESERVED'
                            and mv.reserved_until > ?
                      )
                    order by m.received_at asc
                    limit ?
                    for update of m skip locked
                )
                update managed_inbound_messages m
                set content_encrypted = null,
                    content_purged_at = ?,
                    reserved_for_application_id = null,
                    reserved_for_session_id = null,
                    reserved_at = null,
                    reservation_expires_at = null,
                    reservation_token_sha256 = null
                from target t
                where m.id = t.id
                  and m.content_encrypted is not null
                  and m.received_at < ?
                  and (m.reservation_expires_at is null or m.reservation_expires_at <= ?)
                  and not exists (
                      select 1 from mailbox_verifications mv
                      where mv.source_managed_message_id = m.id
                        and mv.status = 'RESERVED'
                        and mv.reserved_until > ?
                  )
                """, contentCutoff, tsNow, tsNow, BATCH, tsNow, contentCutoff, tsNow, tsNow);

        int deleted = db.update("""
                with target as (
                    select m.id from managed_inbound_messages m
                    where m.received_at < ?
                      and (m.reserved_at is null or m.reservation_expires_at is null or m.reservation_expires_at <= ?)
                      and not exists (
                          select 1 from mailbox_verifications mv
                          where mv.source_managed_message_id = m.id
                            and mv.status = 'RESERVED'
                            and mv.reserved_until > ?
                      )
                    order by m.received_at asc
                    limit ?
                    for update of m skip locked
                )
                delete from managed_inbound_messages m
                using target t
                where m.id = t.id
                  and m.received_at < ?
                  and (m.reserved_at is null or m.reservation_expires_at is null or m.reservation_expires_at <= ?)
                  and not exists (
                      select 1 from mailbox_verifications mv
                      where mv.source_managed_message_id = m.id
                        and mv.status = 'RESERVED'
                        and mv.reserved_until > ?
                  )
                """, metadataCutoff, tsNow, tsNow, BATCH, metadataCutoff, tsNow, tsNow);

        int tokens = db.update("""
                with target as (
                    select token_sha256 from managed_mail_webhook_tokens
                    where received_at < ?
                    order by received_at asc
                    limit ?
                    for update skip locked
                )
                delete from managed_mail_webhook_tokens t
                using target k
                where t.token_sha256 = k.token_sha256
                  and t.received_at < ?
                """, tokenCutoff, BATCH, tokenCutoff);

        db.update("""
                with target as (
                    select id from mailbox_verifications
                    where status in ('EXPIRED', 'RELEASED')
                      and created_at < ?
                    order by created_at asc
                    limit ?
                    for update skip locked
                )
                delete from mailbox_verifications mv
                using target t
                where mv.id = t.id
                  and mv.status in ('EXPIRED', 'RELEASED')
                  and mv.created_at < ?
                """, metadataCutoff, BATCH, metadataCutoff);

        db.update("""
                with target as (
                    select id from emails
                    where classification = 'VERIFICATION_CODE'
                      and ingested_at < ?
                      and (body_retained = true or (subject is not null and subject <> '[Verification email — subject redacted]'))
                    order by ingested_at asc
                    limit ?
                    for update skip locked
                )
                update emails e
                set body_text = '',
                    body_retained = false,
                    subject = '[Verification email — subject redacted]'
                from target t
                where e.id = t.id
                """, contentCutoff, BATCH);

        return new Result(erased, deleted, tokens);
    }
}
