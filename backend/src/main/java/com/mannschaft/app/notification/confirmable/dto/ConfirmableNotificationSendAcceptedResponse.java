package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDeliveryStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * CMP-260920-1040 F04.9 確認通知の送信API応答（軍議第8版確定稿 §3.3・AC-19）。
 *
 * <p>非同期の宛先指定送信は 202 Accepted で本体を返す。受信者行は API 内で作らないため、
 * {@code estimatedRecipientCount} は送信の時点でプレビューした見込み件数（{@code ConfirmableRecipientPreviewService}）
 * であり、実際に作られる受信者数（{@code totalRecipientCount}）とは一致しないことがある（ワーカー処理時点で変動）。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmableNotificationSendAcceptedResponse {

    private Long id;

    private ConfirmableNotificationDeliveryStatus deliveryStatus;

    /** 受け付けの時点でプレビューした見込み受信者数（AC-19・AC-20の判定に使用した値）。 */
    private long estimatedRecipientCount;
}
