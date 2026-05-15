package com.mannschaft.app.pointcard.dto;

import java.util.UUID;

/**
 * F18 Phase 2 S2B — 顧客追加用 QR コード情報レスポンス DTO。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §3.3 UC-8 / §12.2
 *
 * <p>サーバーは URL のみ返却し、実際の QR 画像生成はフロントエンドの qrcode ライブラリで行う。
 * モバイルアプリは {@code deepLinkUrl}、Web は {@code webUrl} を使ってウォレット追加画面に誘導する。
 *
 * @param providerId    プロバイダーの UUID
 * @param displayName   プロバイダー表示名（QR 周辺に表示する用）
 * @param deepLinkUrl   モバイルアプリ用ディープリンク（{@code mannschaft://...}）
 * @param webUrl        Web 用 URL（PWA・ブラウザフォールバック）
 */
public record CustomerQrResponse(
        UUID providerId,
        String displayName,
        String deepLinkUrl,
        String webUrl
) {
}
