package com.mannschaft.app.team.service;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.outbox.NotificationOutboxAppendedEvent;
import com.mannschaft.app.notification.outbox.NotificationOutboxMessageKind;
import com.mannschaft.app.notification.outbox.NotificationOutboxPayload;
import com.mannschaft.app.notification.outbox.NotificationOutboxPayloadCodec;
import com.mannschaft.app.role.fanout.OrganizationAdminsFanoutRecipientSource;
import com.mannschaft.app.role.fanout.TeamAffiliationOperatorsFanoutRecipientSource;
import com.mannschaft.app.team.repository.TeamNotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * {@link TeamAffiliationNotifier} の実装。加盟の通知を、呼び出し元（業務）の tx の中で
 * <b>team ドメインの outbox 表</b>（{@code team_notification_outbox}）に1行書く（docs/architecture/notification_outbox.md §2・§3.1）。
 *
 * <p>通知ドメインの表へは書かない。コミット後に通知ドメインの relay が outbox を取り込み、fan-out ジョブを作る。
 * 業務と通知の予約は同時に確定し（業務が巻き戻れば outbox の行も消える。OB02）、outbox の書き込みが失敗すれば
 * 業務ごと巻き戻る（OB02a・OB14）。{@code MANDATORY} は同じ team ドメインの呼び出し元に対するもので越境ではない（D-3P-1）。</p>
 *
 * <p>受信者ソースは2つ（どちらも受信者が数名のため {@code shard_count=1} 固定）:
 * {@code ORGANIZATION_ADMINS}（{@code scope_ref}=組織 ID）と
 * {@code TEAM_AFFILIATION_OPS}（{@code scope_ref}=チーム ID）。</p>
 *
 * <p>冪等キーは {@code UUID.nameUUIDFromBytes("F01.2.1:" + notificationType + ":" + membershipId)}（F01.2.1 §6.7）。
 * outbox の UNIQUE と、取り込み時の fan-out ジョブの {@code source_event_uuid} の両方に同じ値を使う。
 * 重複は {@code INSERT ... ON DUPLICATE KEY UPDATE id = id} で吸収し、例外を投げず呼び出し側の tx を
 * rollback-only にしない（OB05）。</p>
 *
 * <p>同じ tx の中で起こしのイベント {@link NotificationOutboxAppendedEvent} を publish する
 * （relay が AFTER_COMMIT で即 drain する。届かなくても予備ポーラーが拾う）。</p>
 */
@Component
@RequiredArgsConstructor
public class OutboxTeamAffiliationNotifier implements TeamAffiliationNotifier {

    private final TeamNotificationOutboxRepository outboxRepository;
    private final NotificationOutboxPayloadCodec payloadCodec;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(TeamAffiliationNotice notice) {
        FanoutEnqueueCommand command = toCommand(notice);
        String payloadJson = payloadCodec.encode(NotificationOutboxPayload.fanout(command));
        outboxRepository.insertIdempotent(
                UuidV7.generate(),
                command.idempotencyKey(),
                NotificationOutboxMessageKind.FANOUT.name(),
                NotificationOutboxPayloadCodec.CURRENT_VERSION,
                payloadJson,
                notice.notificationType().name(),
                notice.organizationId(),
                TeamNotificationOutboxSource.micros(Instant.now(clock)));
        eventPublisher.publishEvent(new NotificationOutboxAppendedEvent(TeamNotificationOutboxSource.SOURCE_NAME));
    }

    private static FanoutEnqueueCommand toCommand(TeamAffiliationNotice notice) {
        String scopeType = switch (notice.recipientScope()) {
            case ORGANIZATION_ADMINS -> OrganizationAdminsFanoutRecipientSource.SCOPE_TYPE;
            case TEAM_AFFILIATION_OPERATORS -> TeamAffiliationOperatorsFanoutRecipientSource.SCOPE_TYPE;
        };
        return new FanoutEnqueueCommand(
                scopeType,
                String.valueOf(notice.recipientScopeId()),
                notice.notificationType().name(),
                idempotencyKey(notice),
                notice.organizationId(),
                notice.notificationType().getPriority(),
                notice.actorUserId(),
                notice.notificationType().getSourceType(),
                notice.membershipId(),
                notice.actionUrl(),
                false,
                notice.messageKind(),
                notice.messageArgs(),
                FanoutEnqueueCommand.ShardMode.FIXED_SINGLE);
    }

    /** 冪等キー（§6.7）。同じ通知種別・同じ加盟 ID の二重書き込みを1件に収束させる。 */
    static UUID idempotencyKey(TeamAffiliationNotice notice) {
        return UUID.nameUUIDFromBytes(
                ("F01.2.1:" + notice.notificationType().name() + ":" + notice.membershipId())
                        .getBytes(StandardCharsets.UTF_8));
    }
}
