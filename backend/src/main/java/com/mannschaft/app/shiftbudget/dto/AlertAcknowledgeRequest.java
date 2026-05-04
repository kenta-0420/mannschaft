package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * F08.7 シフト予算 警告承認応答 リクエスト DTO（API #10）。
 *
 * <p>設計書 F08.7 (v1.2) §6.2.5 に準拠。</p>
 *
 * <p>{@code comment} は任意（NULL 許容）。長文の付記を防ぐため上限 500 文字で制限する。</p>
 */
public record AlertAcknowledgeRequest(

        @JsonProperty("comment")
        @Size(max = 500, message = "コメントは500文字以内で入力してください")
        String comment
) {
    public AlertAcknowledgeRequest {
        // record の compact constructor — comment は任意なので validation のみ Bean Validation に委譲
    }
}
