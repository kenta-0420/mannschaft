package com.mannschaft.app.school.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 生徒・保護者向け開示済み評価情報レスポンスDTO（F03.13 Phase 15）。 */
public record DisclosedEvaluationResponse(
        Long evaluationId,
        Long ruleId,
        String ruleName,
        /** 評価ステータス (OK/WARNING/RISK/VIOLATION) */
        String status,
        /** 開示モード (WITH_NUMBERS/WITHOUT_NUMBERS/MEETING_REQUEST_ONLY) */
        String mode,
        /** 担任メッセージ（任意） */
        String message,
        LocalDateTime disclosedAt,
        /** 出席率（WITH_NUMBERS の場合のみ） */
        BigDecimal currentRate,
        /** 残余許容欠席日数（WITH_NUMBERS の場合のみ） */
        Integer remainingAllowedDays
) {
}
