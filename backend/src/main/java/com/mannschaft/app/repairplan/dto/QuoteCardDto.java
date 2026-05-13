package com.mannschaft.app.repairplan.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 業者見積カード DTO（F08.8 Phase 4）。
 *
 * <p>visibility フィルタ適用後の値を返す。</p>
 * <ul>
 *   <li>HIDDEN → vendorNameSnapshot=null, amount=null</li>
 *   <li>ANONYMIZED → vendorNameSnapshot="業者A"等, amount=レンジ表示</li>
 *   <li>FULL → そのまま</li>
 * </ul>
 *
 * @param id                   カードID
 * @param kanbanId             所属カンバンID
 * @param vendorId             業者ID
 * @param vendorNameSnapshot   業者名（visibility フィルタ後）
 * @param stage                現在ステージ
 * @param amount               見積金額（visibility フィルタ後）
 * @param amountRangeLabel     金額レンジラベル（ANONYMIZED 時に使用）
 * @param complianceCheckStatus 反社チェック状態
 * @param displayOrder         表示順
 * @param createdAt            作成日時
 */
public record QuoteCardDto(
        UUID id,
        UUID kanbanId,
        Long vendorId,
        String vendorNameSnapshot,
        String stage,
        Long amount,
        String amountRangeLabel,
        String complianceCheckStatus,
        Integer displayOrder,
        LocalDateTime createdAt
) {}
