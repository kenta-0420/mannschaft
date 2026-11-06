package com.mannschaft.app.notification.outbox;

import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;

import java.util.Collection;
import java.util.LinkedHashSet;
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

    /**
     * 宛先集合（見出し＋送信時の宛先チーム）を伴う fan-out の依頼（F01.2.1 §8.5.3。6-E' の告知 push）。
     *
     * @param fanout             fan-out ジョブの登録内容（{@code scope_ref} は {@code audienceSnapshotId} の UUID 文字列）
     * @param audienceSnapshotId 宛先集合のキー
     * @param teamIds            送信時の宛先チーム（重複は除いて並びを保つ。0 件なら直属メンバーだけに届く）
     */
    public static NotificationOutboxPayload fanoutWithAudience(FanoutEnqueueCommand fanout, UUID audienceSnapshotId,
                                                               Collection<Long> teamIds) {
        return new NotificationOutboxPayload(NotificationOutboxMessageKind.FANOUT_WITH_AUDIENCE, fanout,
                audienceSnapshotId, List.copyOf(new LinkedHashSet<>(teamIds)));
    }
}
