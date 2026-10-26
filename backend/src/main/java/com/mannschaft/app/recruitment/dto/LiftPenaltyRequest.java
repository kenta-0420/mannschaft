package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.PenaltyLiftReason;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F03.11 Phase 5b: ペナルティ手動解除リクエスト DTO。
 */
@Getter
@NoArgsConstructor
public class LiftPenaltyRequest {

    @NotNull
    private PenaltyLiftReason liftReason;

    /** 解除メモ（任意）。 */
    private String liftNote;
}
