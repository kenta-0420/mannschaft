package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.api.ModuleEventNoticeOutboxApi;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.fanout.FanoutChunkSink;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.ByteBuffer;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;

/** jobのPKロック、本体→archiveのcurrent read、採番保存を一つの独立TXで行う。 */
@Component
@RequiredArgsConstructor
public class ModuleEventNoticeFanoutChunkSink implements FanoutChunkSink {
    private static final String TYPE = "MODULE_EVENT_NOTICE";
    private final ModuleEventNoticeOutboxApi api;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final NotificationDispatchService dispatch;
    @Override public String notificationType() { return TYPE; }

    @Override public ChunkResult processChunk(UUID jobId, Long noticeRef, List<Long> userIds) {
        requireReference(jobId, noticeRef);
        if (userIds == null || userIds.size() > 500 || userIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("M通知候補は正数IDの有界リストが必要");
        }
        List<Long> candidates = userIds.stream().distinct().sorted().toList();
        Write result = transaction().execute(status -> {
            Map<String,Object> job = lockJob(jobId);
            var descriptor = descriptor(noticeRef);
            validateJob(job, descriptor);
            if (!"RUNNING".equals(job.get("status"))) return new Write(List.of(), true);
            // 公開APIがcaller TXを停止して実業務の現在判定を行う。未解決を正常0へ変換しない。
            var eligible = api.eligibleRecipients(noticeRef, candidates)
                    .orElseThrow(() -> new IllegalStateException("M通知の現在配送判定が未解決"));
            var recipients = new LinkedHashMap<Long,FanoutRecipient>();
            for (var recipient : eligible) {
                if (recipient == null || !candidates.contains(recipient.getUserId())
                        || recipients.putIfAbsent(recipient.getUserId(), new FanoutRecipient(recipient.getUserId(), recipient.getLocale())) != null) {
                    throw new IllegalStateException("M通知の現在候補が入力集合と一致しない");
                }
            }
            if (recipients.isEmpty()) return new Write(List.of(), false);
            Set<Long> present = currentUsers("notifications", noticeRef, recipients.keySet());
            present.addAll(currentUsers("notifications_archive", noticeRef, recipients.keySet()));
            recipients.keySet().removeAll(present);
            if (recipients.isEmpty()) return new Write(List.of(), false);
            var messages = jdbc.queryForList("SELECT locale,title,body FROM notification_fanout_job_messages WHERE job_id = ?", binary(jobId));
            var byLocale = new HashMap<String,Map<String,Object>>();
            for (var message : messages) {
                if (byLocale.putIfAbsent((String)message.get("locale"), message) != null) {
                    throw new IllegalStateException("M通知文面のlocaleが重複");
                }
            }
            var seeds = new ArrayList<NotificationEntity>();
            for (var recipient : recipients.values()) {
                var message = byLocale.get(recipient.locale());
                if (message == null || !(message.get("title") instanceof String title) || title.isBlank()
                        || !(message.get("body") instanceof String body) || body.isBlank()) {
                    throw new IllegalStateException("M通知の固定文面が欠落");
                }
                seeds.add(NotificationEntity.builder().userId(recipient.userId())
                        .organizationId(descriptor.getImpact().getOrganizationId()).notificationType(TYPE)
                        .priority(NotificationPriority.NORMAL).title(title).body(body).sourceType(TYPE).sourceId(noticeRef)
                        .scopeType(NotificationScopeType.valueOf(descriptor.getImpact().getScopeType().name()))
                        .scopeId(descriptor.getImpact().getScopeId()).actorId(null).actionUrl(null)
                        .isRead(false).createdAt(LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE)).build());
            }
            return new Write(insert(seeds), false);
        });
        // REQUIRES_NEWのexecuteがcommitしてから、実ID付きの新規行だけを配信する。
        if (!result.added().isEmpty()) dispatch.dispatchBatch(result.added());
        return new ChunkResult(result.added().size(), result.stopped());
    }

    @Override public void finish(UUID jobId, Long noticeRef) {
        requireReference(jobId, noticeRef);
        transaction().executeWithoutResult(status -> {
            var job = lockJob(jobId);
            validateJob(job, descriptor(noticeRef));
            if (!"RUNNING".equals(job.get("status"))) return;
            api.eligibleRecipients(noticeRef, List.of())
                    .orElseThrow(() -> new IllegalStateException("M通知の終了判定が未解決"));
            jdbc.update("UPDATE notification_fanout_jobs SET status = 'DONE', updated_at = UTC_TIMESTAMP() WHERE id = ? AND status = 'RUNNING'", binary(jobId));
        });
    }

    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }
    private Map<String,Object> lockJob(UUID id) {
        var rows = jdbc.queryForList("SELECT * FROM notification_fanout_jobs WHERE id = ? FOR UPDATE", binary(id));
        if (rows.size() != 1) throw new IllegalStateException("M通知jobが存在しない");
        return rows.getFirst();
    }
    private ModuleEventNoticeDescriptor descriptor(Long reference) {
        return api.readDescriptor(reference).orElseThrow(() -> new IllegalStateException("M通知descriptorが未解決"));
    }
    private void validateJob(Map<String,Object> job, ModuleEventNoticeDescriptor descriptor) {
        Long reference = descriptor.getNoticeRef();
        if (!TYPE.equals(job.get("scope_type")) || !TYPE.equals(job.get("source_type"))
                || !TYPE.equals(job.get("notification_type")) || !reference.toString().equals(job.get("scope_ref"))
                || !(job.get("source_id") instanceof Number source) || source.longValue() != reference
                || !Arrays.equals(binary(descriptor.getNoticeKey()), (byte[])job.get("source_event_uuid"))
                || !Objects.equals(number(job.get("organization_id")), descriptor.getImpact().getOrganizationId())
                || ((Number)job.get("shard_count")).intValue() != 1 || ((Number)job.get("shard_index")).intValue() != 0
                || job.get("actor_id") != null || job.get("action_url") != null || !"NORMAL".equals(job.get("priority"))) {
            throw new IllegalStateException("M通知jobの型/参照/key/tenantが不一致");
        }
    }
    private Set<Long> currentUsers(String table, Long reference, Collection<Long> users) {
        // tableは内部の固定2値だけ。非locking snapshotを使うとarchive移送中に再生成するためFOR UPDATE必須。
        String placeholders = String.join(",", Collections.nCopies(users.size(), "?"));
        var args = new ArrayList<Object>(); args.add(TYPE); args.add(reference); args.addAll(users);
        return new HashSet<>(jdbc.queryForList("SELECT user_id FROM " + table
                + " WHERE source_type = ? AND source_id = ? AND user_id IN (" + placeholders + ") ORDER BY id FOR UPDATE", Long.class, args.toArray()));
    }
    private List<NotificationEntity> insert(List<NotificationEntity> seeds) {
        String values = String.join(",", Collections.nCopies(seeds.size(), "(?,?,?,?,?,?,?,?,?,?,?,?,FALSE,UTC_TIMESTAMP())"));
        String sql = "INSERT INTO notifications (user_id,organization_id,notification_type,priority,title,body,source_type,source_id,scope_type,scope_id,action_url,actor_id,is_read,created_at) VALUES " + values;
        var keys = new GeneratedKeyHolder();
        int changed = jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int index = 1;
            for (var seed : seeds) {
                for (Object value : Arrays.asList(seed.getUserId(), seed.getOrganizationId(), TYPE, "NORMAL", seed.getTitle(), seed.getBody(), TYPE,
                        seed.getSourceId(), seed.getScopeType().name(), seed.getScopeId(), null, null)) statement.setObject(index++, value);
            }
            return statement;
        }, keys);
        if (changed != seeds.size() || keys.getKeyList().size() != seeds.size()) {
            throw new IllegalStateException("M通知の保存行数/採番数が不一致");
        }
        var result = new ArrayList<NotificationEntity>();
        for (int index=0; index<seeds.size(); index++) {
            Object key = keys.getKeyList().get(index).values().iterator().next();
            if (!(key instanceof Number id) || id.longValue() <= 0) throw new IllegalStateException("M通知の採番IDが不正");
            result.add(seeds.get(index).toBuilder().id(id.longValue()).build());
        }
        return List.copyOf(result);
    }
    private static void requireReference(UUID jobId, Long reference) {
        if (jobId == null || reference == null || reference <= 0) throw new IllegalArgumentException("M通知job/refが不正");
    }
    private static Long number(Object value) { return value == null ? null : ((Number)value).longValue(); }
    private static byte[] binary(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private record Write(List<NotificationEntity> added, boolean stopped) { }
}
