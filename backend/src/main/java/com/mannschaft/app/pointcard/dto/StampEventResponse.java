package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.PointCardStampEventEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * スタンプ押印イベント レスポンス DTO。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.2 / §12.2
 *
 * <p><strong>プライバシー方針:</strong>
 * 店主側 API で利用されるが、対象顧客カードの暗号化フィールド
 * （{@code displayName} / {@code nickname} / {@code barcodeValue} / {@code memo}）は
 * 一切含めない。本 DTO は組織スコープでのスタンプ証跡として
 * 「カード ID + プロバイダー + 押印者 + delta + メモ」のみを返す。
 *
 * @param id                       スタンプイベント ID（UUIDv7）
 * @param cardId                   対象カード ID
 * @param providerId               プロバイダー ID
 * @param providerDisplayName      プロバイダー表示名（運営マスタなので暗号化対象外）
 * @param organizationId           プロバイダー発行組織 ID
 * @param delta                    スタンプ増減数
 * @param pressedByUserId          押印者ユーザー ID
 * @param pressedByUserDisplayName 押印者ユーザー表示名（{@code users.display_name} は平文 50 文字）
 * @param pressedAt                押印日時
 * @param memo                     メモ（運営側コメント、暗号化対象ではない）
 */
public record StampEventResponse(
        UUID id,
        UUID cardId,
        UUID providerId,
        String providerDisplayName,
        Long organizationId,
        Integer delta,
        Long pressedByUserId,
        String pressedByUserDisplayName,
        OffsetDateTime pressedAt,
        String memo
) {

    /**
     * Entity と関連プロバイダー・押印者表示名から DTO を構築する。
     *
     * @param event              イベント Entity
     * @param provider           プロバイダー Entity（null の場合あり）
     * @param pressedByDisplayName 押印者表示名（null の場合は退会済等）
     */
    public static StampEventResponse from(PointCardStampEventEntity event,
                                          PointCardProviderEntity provider,
                                          String pressedByDisplayName) {
        return new StampEventResponse(
                event.getId(),
                event.getCardId(),
                event.getProviderId(),
                provider != null ? provider.getDisplayName() : null,
                event.getOrganizationId(),
                event.getDelta(),
                event.getPressedByUserId(),
                pressedByDisplayName,
                event.getPressedAt(),
                event.getMemo()
        );
    }
}
