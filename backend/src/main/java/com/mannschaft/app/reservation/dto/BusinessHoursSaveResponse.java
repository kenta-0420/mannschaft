package com.mannschaft.app.reservation.dto;

import java.util.List;

/**
 * 営業時間 PUT の保存＋同期自動生成の統合レスポンス（F03.4.5 §3.2）。
 *
 * <p>営業時間の保存結果（{@code hours}）と、変更のあった曜日の active テンプレを対象にした
 * horizon 28 日生成の結果（{@code generation}）を 1 レスポンスに包む。営業時間の拡大で
 * {@code skippedOutsideHoursCount} に落ちていたセルが自動的に埋まる。</p>
 *
 * <p><b>PUT のみ応答型変更</b>: GET（{@code getBusinessHours}）は {@code BusinessHourResponse[]} のまま
 * 不変であり、GET 消費者（{@code ReservationUnavailabilityManager.vue}）への影響はない（§3.2）。</p>
 */
public record BusinessHoursSaveResponse(
        List<BusinessHourResponse> hours,
        SlotGenerationResultDto generation) {
}
