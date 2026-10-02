package com.mannschaft.app.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 定期予約不可枠 レスポンスDTO（F03.4.5 §4.6）。
 */
@Builder(toBuilder = true)
@Getter
public class RecurringBlockedTimeResponse {

    UUID id;
    Long teamId;
    Long lineId;
    /** 対象ライン名（NameResolver 不要・ライン一括取得で解決。チーム全体は null）。 */
    String lineName;
    String dayOfWeek;
    LocalTime startTime;
    LocalTime endTime;
    String reason;
    Boolean isPublic;
    Boolean isActive;
    @Schema(description = "終了時刻が翌日")
    Boolean endsNextDay;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    /**
     * 強行登録（{@code forceCancelConflicting=true}）で一括キャンセルした予約件数
     * （F03.4.5 §6.2 W2-5・殿の裁定・additive）。
     *
     * <p><b>従来経路（force なし）では null</b> — 既存契約不変。force 指定時は 0 件でも 0 を返し、
     * 「強行モードで実行されたが衝突は無かった」ことを管理者 UI が区別できるようにする。</p>
     */
    Integer forceCancelledCount;
}
