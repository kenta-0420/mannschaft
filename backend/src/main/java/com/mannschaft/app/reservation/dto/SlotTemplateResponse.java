package com.mannschaft.app.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 週間テンプレートレスポンスDTO（F03.4.2 §4）。
 *
 * <p>{@code cellCount} = この帯が 1 日あたり生成する 30 分セル数（BE 導出）。</p>
 */
@Builder(toBuilder = true)
@Getter
public class SlotTemplateResponse {

    UUID id;
    String name;
    Long lineId;
    /** 対象ライン名（共通枠テンプレは null）。 */
    String lineName;
    /** 曜日（正準3文字大文字 MON..SUN）。 */
    String dayOfWeek;
    LocalTime startTime;
    LocalTime endTime;
    @Schema(description = "終了時刻が翌日")
    Boolean endsNextDay;
    Integer capacity;
    Long staffUserId;
    String staffName;
    String title;
    BigDecimal price;
    String approvalMode;
    Boolean isActive;
    /** 1 日あたり生成する 30 分セル数（(end - start) / 30）。 */
    Integer cellCount;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
