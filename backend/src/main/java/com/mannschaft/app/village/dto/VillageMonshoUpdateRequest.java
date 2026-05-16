package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 村紋（monsho）更新リクエスト DTO（F17 Phase 2 U7 / 設計書 §11.1 / §13.2）。
 *
 * <p>Phase 2 シンプル版: クライアントは別途プリサインド URL 等で R2 にアップロード後、
 * 払い出された {@code r2Key}（例: {@code village/{villageId}/monsho/{filename}}）を
 * 本 API に渡して {@code villages.monsho_r2_key} を更新する。</p>
 *
 * @param r2Key R2 オブジェクトキー（必須・255 文字以内）
 */
public record VillageMonshoUpdateRequest(

        @NotBlank
        @Size(max = 255)
        String r2Key
) {
}
