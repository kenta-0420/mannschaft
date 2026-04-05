package com.mannschaft.app.budget.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取引取消（反転仕訳）リクエスト。
 */
public record ReverseTransactionRequest(

        @NotBlank
        @Size(max = 200)
        String reason
) {
}
