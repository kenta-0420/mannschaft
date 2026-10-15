package com.mannschaft.app.notification.confirmable.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * CMP-260920-1040 F04.9 宛先の見込み件数プレビューレスポンス（軍議第8版確定稿 §3.3）。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmableRecipientPreviewResponse {

    /** 見込み受信者数（送信者本人除外・一意化後） */
    private long estimatedRecipientCount;
}
