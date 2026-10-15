package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ReservationCancelScope;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約キャンセルリクエストDTO。
 *
 * <p>全 final ＋ 単一コンストラクタを維持すること（Jackson creator 解決・
 * {@code feedback_dto_all_final_multi_constructor_jackson_no_creators}）。</p>
 */
@Getter
@RequiredArgsConstructor
public class CancelReservationRequest {

    @Size(max = 500)
    private final String reason;

    /**
     * キャンセルの適用範囲（F03.4.5 §6.2 W2-5・additive）。
     *
     * <p>省略（null）＝ {@link ReservationCancelScope#THIS_ONLY}（既定・従来挙動と完全に同一）。
     * {@link ReservationCancelScope#THIS_AND_FOLLOWING} を指定すると、同一 series の
     * <b>当該日以降</b>の自分の active 予約を続けてキャンセルする（過去回は不変・AC-5-7）。
     * series に属さない単発予約に指定しても従来どおり 1 件だけがキャンセルされる（無害）。</p>
     *
     * <p>本フィールドは<b>会員のキャンセル動線（マイ予約）専用</b>である。管理者キャンセル
     * （{@code cancelByAdmin}）は 1 件ずつの運用判断であり series 一括の要件が無いため参照しない。</p>
     */
    private final ReservationCancelScope scope;
}
