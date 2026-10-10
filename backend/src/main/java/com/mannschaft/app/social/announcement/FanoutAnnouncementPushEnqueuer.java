package com.mannschaft.app.social.announcement;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.notification.fanout.NotificationFanoutAudienceService;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.role.fanout.OrgTeamsFanoutRecipientSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * {@link AnnouncementPushEnqueuer} の実装。宛先集合と fan-out ジョブを呼び出し元のトランザクションで登録する
 * （F01.2.1 §8.5.1・§8.5.3）。
 *
 * <p>受信者の母集団は Worker がチャンクごとに所属を解決する（{@code OrgTeamsFanoutRecipientSource}）。
 * シャードは AUTO（{@code shard_count=0}）で登録し、評価は Worker に任せる。</p>
 *
 * <p>本クラス自身は {@code @Transactional} を持たない（トランザクションは呼び出し元のもの。
 * 登録側の2つのサービスが {@code Propagation.MANDATORY} で進行中のトランザクションを要求する）。</p>
 */
@Component
@RequiredArgsConstructor
public class FanoutAnnouncementPushEnqueuer implements AnnouncementPushEnqueuer {

    /** push の通知種別（アンケート公開通知と同じ。F01.2.1 §8.5.3）。 */
    static final String PUSH_NOTIFICATION_TYPE = "SURVEY_CREATED";

    private final NotificationFanoutAudienceService fanoutAudienceService;
    private final NotificationFanoutJobService fanoutJobService;

    @Override
    public void enqueue(NarrowedAnnouncementPush push) {
        UUID audienceSnapshotId = audienceSnapshotId(push.feedId());
        fanoutAudienceService.registerAudience(audienceSnapshotId, push.organizationId(), push.teamIds());
        fanoutJobService.enqueueInCurrentTransaction(new FanoutEnqueueCommand(
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
                FanoutEnqueueCommand.ShardMode.AUTO));
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
