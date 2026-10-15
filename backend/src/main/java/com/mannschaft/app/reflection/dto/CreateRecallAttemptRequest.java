package com.mannschaft.app.reflection.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.reflection.RecallSelfRating;
import jakarta.validation.constraints.NotNull;

/**
 * 想起テスト保存リクエスト（F06.5・§7 #10）。保存＝開示で revealed_at 記録・original 返却。
 *
 * @param recalledContent 思い出して書いた内容（必須・structured_content と同形 or 自由テキスト）
 * @param selfRating      自己評価（必須・REMEMBERED/PARTIAL/FORGOT）
 */
public record CreateRecallAttemptRequest(

        @NotNull(message = "想起内容を入力してください")
        JsonNode recalledContent,

        @NotNull(message = "自己評価を選択してください")
        RecallSelfRating selfRating
) {
}
