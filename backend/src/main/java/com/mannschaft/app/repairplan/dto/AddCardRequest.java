package com.mannschaft.app.repairplan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 見積カード追加リクエスト（F08.8 Phase 4）。
 *
 * @param vendorId           業者ID（必須）
 * @param vendorNameSnapshot 業者名スナップショット（必須・最大150文字）
 * @param amount             見積金額（null可: 受領前は未入力可）
 * @param breakdownJson      内訳 JSON（null可）
 */
public record AddCardRequest(
        @NotNull Long vendorId,
        @NotBlank @Size(max = 150) String vendorNameSnapshot,
        Long amount,
        String breakdownJson
) {}
