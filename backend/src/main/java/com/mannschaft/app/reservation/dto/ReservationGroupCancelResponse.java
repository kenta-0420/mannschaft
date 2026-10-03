package com.mannschaft.app.reservation.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 予約グループ一括キャンセルレスポンスDTO（F03.4.3 §4）。
 *
 * @param groupId        予約グループID
 * @param status         遷移後ステータス（常に CANCELLED）
 * @param cancelledAt    キャンセル日時
 * @param cancelledBy    キャンセル実行者（USER / ADMIN）
 * @param cancelledCount キャンセルされた枠数（兄弟行数）
 */
public record ReservationGroupCancelResponse(
        UUID groupId,
        String status,
        LocalDateTime cancelledAt,
        String cancelledBy,
        int cancelledCount) {
}
