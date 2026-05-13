package com.mannschaft.app.repairplan.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 見積カードのステージ移動リクエスト（F08.8 Phase 4）。
 *
 * @param newStage 移動先ステージ（REQUESTED / RECEIVED / UNDER_REVIEW / SHORTLISTED / SELECTED / REJECTED）
 */
public record MoveCardRequest(
        @NotBlank String newStage
) {}
