package com.mannschaft.app.circulation.dto;

import jakarta.validation.constraints.Size;

/**
 * 押印訂正リクエスト。
 *
 * <p>F05.2 Phase 11 第三陣 3-B。受信者本人が自分の押印を訂正する際の理由を任意で受け取る。
 * status は PENDING に戻され、再押印できる状態になる。</p>
 *
 * @param reason 訂正理由（任意、255 文字以内）
 */
public record StampCorrectionRequest(
        @Size(max = 255) String reason
) {
}
