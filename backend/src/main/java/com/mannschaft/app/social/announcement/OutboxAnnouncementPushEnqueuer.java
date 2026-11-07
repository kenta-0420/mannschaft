package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.notification.outbox.NotificationOutboxAppendedEvent;
import com.mannschaft.app.notification.outbox.NotificationOutboxMessageKind;
import com.mannschaft.app.notification.outbox.NotificationOutboxPayload;
import com.mannschaft.app.notification.outbox.NotificationOutboxPayloadCodec;
import com.mannschaft.app.role.fanout.OrgTeamsFanoutRecipientSource;
import com.mannschaft.app.social.repository.SocialNotificationOutboxRepository;
import com.mannschaft.app.social.service.SocialNotificationOutboxSource;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@link AnnouncementPushEnqueuer} の実装。宛先を絞った告知の push の予約を、告知の作成と同じ tx の中で
 * <b>social ドメインの outbox 表</b>（{@code social_notification_outbox}）に1行書く
 * （F01.2.1 §8.5.3、docs/architecture/notification_outbox.md §2・§3.1）。
 *
 * <p>通知ドメインの表（宛先集合・fan-out ジョブ）へは書かない。コミット後に通知ドメインの relay が outbox を取り込み、
 * 宛先集合（見出し＋送信時の宛先チーム）と {@code ORGANIZATION_TEAMS} の fan-out ジョブを1つの tx で冪等に登録する
 * （{@code message_kind=FANOUT_WITH_AUDIENCE}）。告知が巻き戻れば outbox の行も消え、outbox の書き込みが失敗すれば
 * 告知ごと巻き戻る（AC-H21）。{@code MANDATORY} は同じ social ドメインの呼び出し元（{@code AnnouncementBroadcastService}）
 * に対するもので越境ではない（D-3P-1）。</p>
 *
 * <p>宛先チームは送信時の集合を payload に固定する（AC-H21b。relay の前にチームが宛先グループへ移っても変わらない）。
 * 受信者の母集団は Worker がチャンクごとに所属を解決する（{@code OrgTeamsFanoutRecipientSource}）。
 * シャードは AUTO（{@code shard_count=0}）で登録し、評価は Worker に任せる。</p>
 *
 * <p>冪等キーと宛先集合のキーはフィード ID から決定的に導く。outbox の UNIQUE と、取り込み時の fan-out ジョブの
 * {@code source_event_uuid} の両方に同じ冪等キーを使う。重複は {@code INSERT ... ON DUPLICATE KEY UPDATE id = id}
 * で吸収し、呼び出し側の tx を rollback-only にしない。</p>
 *
 * <p>同じ tx の中で起こしのイベント {@link NotificationOutboxAppendedEvent} を publish する
 * （relay が AFTER_COMMIT で即 drain する。届かなくても予備ポーラーが拾う）。</p>
 */
@Component
@RequiredArgsConstructor
public class OutboxAnnouncementPushEnqueuer implements AnnouncementPushEnqueuer {

    /** push の通知種別（アンケート公開通知と同じ。F01.2.1 §8.5.3）。 */
    static final String PUSH_NOTIFICATION_TYPE = "SURVEY_CREATED";

    private final SocialNotificationOutboxRepository outboxRepository;
    private final NotificationOutboxPayloadCodec payloadCodec;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(NarrowedAnnouncementPush push) {
        UUID audienceSnapshotId = audienceSnapshotId(push.feedId());
        FanoutEnqueueCommand command = new FanoutEnqueueCommand(
                OrgTeamsFanoutRecipientSource.SCOPE_TYPE,
                audienceSnapshotId.toString(),
                PUSH_NOTIFICATION_TYPE,
                idempotencyKey(push.feedId()),
                push.organizationId(),
                NotificationPriority.NORMAL,
                push.actorUserId(),
                "SURVEY",
                push.contentId(),
                "/surveys/" + push.contentId(),
                push.includeSupporters(),
                FanoutMessageKind.SURVEY_PUBLISHED,
                List.of(push.title()),
                FanoutEnqueueCommand.ShardMode.AUTO);
        String payloadJson = payloadCodec.encode(
                NotificationOutboxPayload.fanoutWithAudience(command, audienceSnapshotId, push.teamIds()));
        outboxRepository.insertIdempotent(
                UuidV7.generate(),
                command.idempotencyKey(),
                NotificationOutboxMessageKind.FANOUT_WITH_AUDIENCE.name(),
                NotificationOutboxPayloadCodec.CURRENT_VERSION,
                payloadJson,
                PUSH_NOTIFICATION_TYPE,
                push.organizationId(),
                SocialNotificationOutboxSource.micros(Instant.now(clock)));
        eventPublisher.publishEvent(new NotificationOutboxAppendedEvent(SocialNotificationOutboxSource.SOURCE_NAME));
    }

    /** 宛先集合のキー（フィード ID から決定的に導く）。 */
    static UUID audienceSnapshotId(Long feedId) {
        return UUID.nameUUIDFromBytes(("F02.8:broadcast-audience:" + feedId).getBytes(StandardCharsets.UTF_8));
    }

    /** 冪等キー（フィード ID から決定的に導く。同じフィードの二重 enqueue を1件に収束させる）。 */
    static UUID idempotencyKey(Long feedId) {
        return UUID.nameUUIDFromBytes(("F02.8:broadcast:" + feedId).getBytes(StandardCharsets.UTF_8));
    }
}
