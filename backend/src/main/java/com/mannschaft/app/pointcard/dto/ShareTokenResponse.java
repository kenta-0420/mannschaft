package com.mannschaft.app.pointcard.dto;

import java.time.OffsetDateTime;

/**
 * 一時トークン発行レスポンス DTO（F18 Phase 3 第二陣 2A）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §16 / §9
 *
 * <p>顧客側マイページから {@code POST /api/v1/point-cards/{cardId}/share-tokens} を
 * 呼び出した際に返却される。フロントエンドはこの token を QR コードに変換して画面表示し、
 * 店主側端末が読み取ると {@code POST /api/v1/organizations/{orgId}/point-cards/resolve-by-token}
 * 経由でカード ID を特定できる。
 *
 * <p>TTL は 5 分（Valkey の {@code SET NX EX 300}）で固定。
 * {@code deepLinkUrl} は店主アプリ側でディープリンクとして直接読み取らせる用途も想定する。
 *
 * @param token       一時トークン（UUID v4 / 5 分後に Valkey 上から自動消滅）
 * @param expiresAt   トークン失効日時（生成時刻 + 5 分、ISO 8601 / UTC）
 * @param deepLinkUrl 店主アプリ用ディープリンク URL（{@code mannschaft://wallet/share?token=...}）
 */
public record ShareTokenResponse(
        String token,
        OffsetDateTime expiresAt,
        String deepLinkUrl
) {
}
