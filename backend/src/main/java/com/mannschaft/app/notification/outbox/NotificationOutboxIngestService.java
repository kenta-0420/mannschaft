package com.mannschaft.app.notification.outbox;

import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.fanout.NotificationFanoutAudienceService;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * outbox の依頼を通知ドメインに取り込む（docs/architecture/notification_outbox.md §4.3）。
 *
 * <p>relay とは<b>別の Bean</b>にし、{@code @Transactional(propagation = REQUIRES_NEW)} で独立した tx にする。
 * 起こしの executor が飽和して CallerRuns になり、AFTER_COMMIT の中で同期実行されても、取り込みが既に完了した
 * 業務の tx に参加して消えることがないようにするため（OB15・OB16）。触るのは通知ドメインの表だけ。</p>
 *
 * <p>冪等: {@code FANOUT} は {@link NotificationFanoutJobService} の冪等 SQL 版で登録する（同じ冪等キーの2回目は
 * 既存のジョブに着く）。二重取り込みは結果が1件に収束する。</p>
 *
 * <p>{@code FANOUT_WITH_AUDIENCE}（6-E' の告知 push）は、宛先集合（見出し＋送信時の宛先チーム）と fan-out ジョブを
 * <b>この1つの tx</b> で登録する（AC-H21a: 途中で落ちればどちらも残らず、outbox は PENDING に戻って再試行される）。
 * 宛先集合の登録も冪等 SQL（{@link NotificationFanoutAudienceService#registerAudience}）なので、同じ依頼を2回・同時に
 * 取り込んでも見出し1件・宛先チームは過不足なし・ジョブ1件に収束する（AC-H21d）。デッドロックなどの失敗は例外として
 * relay に返し、再試行の経路に乗せる。</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationOutboxIngestService {

    private final NotificationFanoutJobService fanoutJobService;
    private final NotificationFanoutAudienceService fanoutAudienceService;

    /** 依頼を1件取り込む。失敗は例外として relay へ伝える（relay が outbox 側に失敗を記録する）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ingest(NotificationOutboxPayload payload) {
        if (payload.kind() == null) {
            throw new IllegalArgumentException("取り込みの種類が無い");
        }
        if (payload.fanout() == null) {
            throw new IllegalArgumentException(payload.kind() + " の依頼に fan-out の内容が無い");
        }
        switch (payload.kind()) {
            case FANOUT -> fanoutJobService.enqueueInCurrentTransaction(payload.fanout());
            case FANOUT_WITH_AUDIENCE -> ingestWithAudience(payload);
        }
    }

    /** 宛先集合とジョブを同じ tx で登録する（宛先集合が先。ジョブの {@code scope_ref} が宛先集合を指すため）。 */
    private void ingestWithAudience(NotificationOutboxPayload payload) {
        FanoutEnqueueCommand fanout = payload.fanout();
        if (payload.audienceSnapshotId() == null || payload.teamIds() == null) {
            throw new IllegalArgumentException("FANOUT_WITH_AUDIENCE の依頼に宛先集合のキーか宛先チームが無い");
        }
        if (!payload.audienceSnapshotId().toString().equals(fanout.scopeRef())) {
            throw new IllegalArgumentException("宛先集合のキーとジョブの scope_ref が食い違う: audienceSnapshotId="
                    + payload.audienceSnapshotId() + " scopeRef=" + fanout.scopeRef());
        }
        if (fanout.organizationId() == null) {
            throw new IllegalArgumentException("FANOUT_WITH_AUDIENCE の依頼に組織 ID が無い");
        }
        fanoutAudienceService.registerAudience(payload.audienceSnapshotId(), fanout.organizationId(),
                payload.teamIds());
        fanoutJobService.enqueueInCurrentTransaction(fanout);
    }
}
