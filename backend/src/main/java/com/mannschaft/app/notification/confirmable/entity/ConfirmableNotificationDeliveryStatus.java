package com.mannschaft.app.notification.confirmable.entity;

/**
 * CMP-260920-1040: 確認通知の配信状態（軍議第8版確定稿 §9.1）。
 *
 * <p>打ち切った理由（キャンセル／期限切れ）は本 ENUM では区別せず、
 * 親の {@link ConfirmableNotificationStatus}（CANCELLED / EXPIRED）でそのまま表す。</p>
 */
public enum ConfirmableNotificationDeliveryStatus {

    /** 受け付けた直後。ワーカーがまだチャンクを処理していない */
    QUEUED,

    /** ワーカーがチャンクを処理中 */
    DELIVERING,

    /** 全チャンクの処理が完了した */
    DELIVERED,

    /** 課金の猶予超過などで途中から配れなくなった */
    PARTIALLY_FAILED,

    /** キャンセルまたは期限切れにより配信を打ち切った（理由は親の status を見る） */
    STOPPED
}
