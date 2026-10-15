package com.mannschaft.app.recruitment.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.11 参加申込の本人キャンセルリクエスト (§9.10)。
 *
 * - acknowledgedFee: 必須。false → 400 + RECRUITMENT_304 (確認モーダル未経由)
 * - feeAmountAtRequest: クライアントが表示していたキャンセル料 (整合性確認用)
 *   サーバ計算値と乖離 → 409 + RECRUITMENT_308 + 再試算結果
 */
@Getter
@RequiredArgsConstructor
public class CancelMyApplicationRequest {

    @NotNull
    private final Boolean acknowledgedFee;

    private final Integer feeAmountAtRequest;
}
