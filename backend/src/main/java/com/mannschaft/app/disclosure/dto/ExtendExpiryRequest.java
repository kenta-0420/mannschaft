package com.mannschaft.app.disclosure.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 重要事項説明書 出力履歴の自動削除予定日延長リクエスト（F09.14 Phase 3-E）。
 *
 * <p>設計書 §5.7 出力ファイル保管期間に基づく延長 API のリクエストボディ。
 * 制約は {@code DisclosureExportService.extendExpiry(...)} 側で検証する:</p>
 * <ul>
 *   <li>{@code newExpiresAt} は現在時刻より未来であること</li>
 *   <li>{@code newExpiresAt} は <strong>本日から 7 年</strong> を超えないこと（最大保管期間）</li>
 * </ul>
 *
 * @param newExpiresAt 新しい自動削除予定日時
 */
public record ExtendExpiryRequest(
        @NotNull LocalDateTime newExpiresAt
) {
}
