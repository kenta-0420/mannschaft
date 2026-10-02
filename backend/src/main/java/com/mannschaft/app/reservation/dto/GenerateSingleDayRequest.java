package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ReservationDayOfWeek;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 臨時営業（単日テンプレ適用）リクエストDTO（F03.4.5 §3.3.2 generate-single-day）。
 *
 * <p>Jackson 手本（親 §4.B）: {@code @Getter @RequiredArgsConstructor}＋全 final＋単一コンストラクタ
 * （{@code @JsonCreator} 地雷回避）。{@code sourceDayOfWeek} は {@link ReservationDayOfWeek} 型のため
 * 不正値（{@code MONDAY}/小文字/その他）は Jackson デシリアライズ失敗で 400。</p>
 *
 * <p>日付境界（明日以降・今日から90日以内）・対象曜日テンプレ 0 件の検証はサービス層で行う
 * （状態検証のため Bean Validation ではなく {@code BusinessException}・§3.3.2）。</p>
 */
@Getter
@RequiredArgsConstructor
public class GenerateSingleDayRequest {

    /** 臨時営業する日（明日以降・今日から90日以内）。 */
    @NotNull
    private final LocalDate date;

    /**
     * 適用する曜日ダイヤ（省略時 = {@code date} の実曜日）。
     * 「日曜だが平日ダイヤで営業」は {@code MON} 等を指定する。
     */
    private final ReservationDayOfWeek sourceDayOfWeek;
}
