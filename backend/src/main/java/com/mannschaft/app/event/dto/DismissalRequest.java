package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 解散通知送信リクエスト DTO。F03.12 §16。
 *
 * <p>主催者がワンタップで全参加者・見守り者に「解散しました」を送るための入力モデル。</p>
 */
@Getter
@RequiredArgsConstructor
public class DismissalRequest {

    /**
     * 解散メッセージ（任意）。
     * 省略時は「解散しました」がデフォルトで使用される。
     */
    @Size(max = 500, message = "解散メッセージは500文字以内で入力してください")
    private final String message;

    /**
     * 実際の終了日時（任意、ISO-8601形式）。
     * 省略時は現在日時として記録される。
     */
    private final LocalDateTime actualEndAt;

    /**
     * 見守り者（保護者）にも通知するか否か（デフォルト true）。
     * false の場合は直接の参加者のみに送信し、ケアリンクの見守り者には送信しない。
     */
    private final Boolean notifyGuardians;

    /**
     * 見守り者通知フラグを解決する。null の場合は true を返す（デフォルト有効）。
     *
     * @return 見守り者通知を行う場合 true
     */
    public boolean isNotifyGuardians() {
        return notifyGuardians == null || notifyGuardians;
    }

    /**
     * 解散メッセージを解決する。null または空の場合はデフォルトメッセージを返す。
     *
     * @return 解散メッセージ（非 null）
     */
    public String resolveMessage() {
        return (message != null && !message.isBlank()) ? message : "解散しました";
    }
}
