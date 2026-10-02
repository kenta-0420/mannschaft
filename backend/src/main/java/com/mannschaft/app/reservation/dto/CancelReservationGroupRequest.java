package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約グループ一括キャンセルリクエストDTO（F03.4.3 §4）。
 *
 * <p>{@code cancelReason} は任意（最大500文字）。全 final・単一コンストラクタ（親 §4.B の Jackson 手本）。</p>
 */
@Getter
@RequiredArgsConstructor
public class CancelReservationGroupRequest {

    /** キャンセル理由（任意）。 */
    @Size(max = 500)
    private final String cancelReason;
}
