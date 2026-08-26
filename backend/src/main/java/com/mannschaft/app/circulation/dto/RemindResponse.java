package com.mannschaft.app.circulation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 手動リマインドレスポンス DTO。
 *
 * <p>Phase 11 第三陣 3-A で追加。</p>
 */
@Getter
@RequiredArgsConstructor
public class RemindResponse {

    /** 文書 ID。 */
    private final Long documentId;

    /**
     * リマインド<b>対象者数</b>（未押印＝{@code PENDING} 受信者数）。
     *
     * <p>Issue #2834 / CMP-056 の非同期化（{@code AFTER_COMMIT} + {@code event-pool}）により、
     * レスポンス返却時点では送信成功数を確定できなくなったため、送信成功数ではなく対象者数を返す。
     * なお是正前の「送信成功数」も、1 件の DB 例外で rollback-only が立ち数えた通知ごと消えていたため
     * 実際の到達件数を意味していなかった。外向き契約を壊さないためフィールド自体は維持する。
     * 実際の配送成否は {@code CirculationReminderNotificationListener} の構造化ログで観測する。</p>
     */
    private final int remindedCount;
}
