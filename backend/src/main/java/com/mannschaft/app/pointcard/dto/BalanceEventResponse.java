package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.entity.PointCardBalanceEventEntity;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.enums.BalanceOperationType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 残高変動イベント レスポンス DTO（F18 Phase 3 第二陣 2B）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §12.1 / §6
 *
 * <p><strong>プライバシー方針:</strong>
 * 店主側 API で利用されるが、対象顧客カードの暗号化フィールド
 * （{@code displayName} / {@code nickname} / {@code barcodeValue} / {@code memo} / {@code last4}）は
 * 一切含めない。本 DTO は組織スコープでの取引証跡として
 * 「カード ID + プロバイダー + 操作者 + 操作種別 + delta + balance_after + 返金参照」のみを返す。
 *
 * @param id                        残高イベント ID（UUIDv7）
 * @param cardId                    対象カード ID
 * @param providerId                プロバイダー ID
 * @param providerDisplayName       プロバイダー表示名（運営マスタなので暗号化対象外）
 * @param organizationId            プロバイダー発行組織 ID
 * @param operationType             操作種別（CHARGE / SPENT / REFUND）
 * @param delta                     残高増減額（CHARGE/REFUND は正、SPENT は負）
 * @param balanceAfter              反映後の残高
 * @param refundOfEventId           返金時の元 event ID（REFUND 以外は null）
 * @param operatedByUserId          操作者ユーザー ID
 * @param operatedByUserDisplayName 操作者ユーザー表示名（{@code users.display_name}）
 * @param operatedAt                操作実施時刻
 * @param note                      任意メモ（運営側コメント、暗号化対象ではない）
 * @param createdAt                 レコード作成時刻
 */
public record BalanceEventResponse(
        UUID id,
        UUID cardId,
        UUID providerId,
        String providerDisplayName,
        Long organizationId,
        BalanceOperationType operationType,
        BigDecimal delta,
        BigDecimal balanceAfter,
        UUID refundOfEventId,
        Long operatedByUserId,
        String operatedByUserDisplayName,
        OffsetDateTime operatedAt,
        String note,
        OffsetDateTime createdAt
) {

    /**
     * Entity と関連プロバイダー・操作者表示名から DTO を構築する。
     *
     * @param event                Entity
     * @param provider             プロバイダー Entity（null の場合あり）
     * @param operatedByDisplayName 操作者表示名（null の場合は退会済等）
     */
    public static BalanceEventResponse from(PointCardBalanceEventEntity event,
                                            PointCardProviderEntity provider,
                                            String operatedByDisplayName) {
        return new BalanceEventResponse(
                event.getId(),
                event.getCardId(),
                event.getProviderId(),
                provider != null ? provider.getDisplayName() : null,
                event.getOrganizationId(),
                event.getOperationType(),
                event.getDelta(),
                event.getBalanceAfter(),
                event.getRefundOfEventId(),
                event.getOperatedByUserId(),
                operatedByDisplayName,
                event.getOperatedAt(),
                event.getNote(),
                event.getCreatedAt()
        );
    }
}
