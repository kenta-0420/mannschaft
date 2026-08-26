package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 週間テンプレート一括生成結果DTO（F03.4.2 §4 generate）。
 */
@Builder(toBuilder = true)
@Getter
public class GenerateSlotsResponse {

    /** 新規 INSERT された枠数。 */
    int generatedCount;
    /** 冪等スキップ（既に同一セルが存在）。 */
    int skippedExistingCount;
    /** 定休日（is_open=FALSE・営業時間未定義含む）でスキップされたセル数。 */
    int skippedClosedDayCount;
    /** 営業時間外にはみ出してスキップされたセル数。 */
    int skippedOutsideHoursCount;
    /** 生成対象期間の開始（tomorrow）。 */
    LocalDate horizonFrom;
    /** 生成対象期間の終了（tomorrow + weeks*7 - 1）。 */
    LocalDate horizonTo;
}
