package com.mannschaft.app.repairplan.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 申し送りパック生成リクエスト（F08.8 Phase 5）。
 *
 * <p>{@code termId} は {@link TeamMemberTerm} の UUID 型主キー。</p>
 */
public record GenerateHandoverPackRequest(

        /** どの任期の申し送りか（必須）。TeamMemberTerm.id の UUID。 */
        @NotNull
        UUID termId,

        /** 任意メモ（2000文字以内）。 */
        @Size(max = 2000)
        String memo,

        /**
         * PII レベル。"STANDARD"（デフォルト）または "ANONYMIZED"。
         * ANONYMIZED の場合、個人情報を除いたデータのみ PDF に含める。
         */
        String piiLevel
) {
    /** piiLevel が null の場合に "STANDARD" を返す正規化ヘルパー。 */
    public String normalizedPiiLevel() {
        if (piiLevel == null || piiLevel.isBlank()) {
            return "STANDARD";
        }
        return piiLevel.toUpperCase();
    }
}
