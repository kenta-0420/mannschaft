package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ReservationDayOfWeek;

import java.util.List;
import java.util.Set;

/**
 * 営業時間保存の内部結果（F03.4.5 §3.2・サービス→コントローラ間の受け渡し）。
 *
 * <p>保存後の営業時間一覧（{@code hours}）と、<b>今回の PUT で変更のあった曜日</b>の集合
 * （{@code changedDays}）を返す。コントローラはこの changedDays を使い、保存 tx コミット後・
 * {@code @Transactional} の外側で「変更曜日の active テンプレのみ」を horizon 28 日生成する
 * （INSERT 量を全テンプレ方式の約 1/7〜2/7 に抑える・§3.2）。</p>
 */
public record BusinessHoursUpdateOutcome(
        List<BusinessHourResponse> hours,
        Set<ReservationDayOfWeek> changedDays) {
}
