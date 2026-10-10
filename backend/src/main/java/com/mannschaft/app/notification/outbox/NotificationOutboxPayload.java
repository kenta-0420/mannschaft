package com.mannschaft.app.notification.outbox;

import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;

import java.util.List;
import java.util.UUID;

/**
 * outbox の {@code payload_json} に入れる取り込みの依頼（docs/architecture/notification_outbox.md §4.1）。
 *
 * <p>JSON 化は {@link NotificationOutboxPayloadCodec} が版つきで行う。中身は通知ドメインの
 * {@link FanoutEnqueueCommand} 一式で、受信者は Worker の処理時点で解決する（宛先集合を伴う告知だけは
 * 送信時のチーム集合を {@link #teamIds} に固定する）。</p>
 *
 * @param kind               取り込みの種類
 * @param fanout             fan-out ジョブの登録内容（冪等キーは {@link FanoutEnqueueCommand#idempotencyKey()}）
 * @param audienceSnapshotId 宛先集合のキー（{@code FANOUT_WITH_AUDIENCE} のみ。{@code FANOUT} は null）
 * @param teamIds            送信時の宛先チーム（{@code FANOUT_WITH_AUDIENCE} のみ。{@code FANOUT} は null）
 */
public record NotificationOutboxPayload(NotificationOutboxMessageKind kind,
                                        FanoutEnqueueCommand fanout,
                                        UUID audienceSnapshotId,
                                        List<Long> teamIds) {

    /** 宛先集合を伴わない fan-out の依頼。 */
    public static NotificationOutboxPayload fanout(FanoutEnqueueCommand fanout) {
        return new NotificationOutboxPayload(NotificationOutboxMessageKind.FANOUT, fanout, null, null);
    }
}
