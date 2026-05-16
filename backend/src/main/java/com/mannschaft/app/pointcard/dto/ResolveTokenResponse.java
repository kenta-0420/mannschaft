package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 一時トークン resolve レスポンス DTO（F18 Phase 3 第二陣 2A）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §16 / §9 / §11
 *
 * <p>店主側 {@code POST /api/v1/organizations/{orgId}/point-cards/resolve-by-token}
 * の結果として返す。GETDEL で 1 回限り消費する Valkey トークンから cardId を特定し、
 * 当該カードと自店プロバイダーの紐付き整合性（IDOR チェック）を Service 層で済ませた上で
 * 返却される。
 *
 * <h2>プライバシー方針（最重要）</h2>
 * <p>暗号化対象（{@code displayName} / {@code nickname} / {@code barcodeValue} / {@code memo} /
 * {@code last4}）の中で、設計書 §11 で「肩越し閲覧防止」のため店主側に露出すべきでない
 * 項目は本 DTO に含めない。具体的には:
 * <ul>
 *   <li>{@code barcodeValue} — 一切返さない（提示モード以外で復号する根拠なし）</li>
 *   <li>顧客の {@code displayName} / {@code nickname} / {@code memo} — 一切返さない</li>
 *   <li>{@code last4} — 末尾 4 桁は店主側に「どのカードか」を最小情報で特定させるため返却可（暗号化対象だが平文カラム保持）</li>
 * </ul>
 *
 * <p>プロバイダー情報は運営マスタなので暗号化対象外。店主側に開示してよい。
 * 顧客がこの組織のスタンプ・残高をいくつ持っているかは「自店の発行物」なので、
 * {@code currentStampCount} / {@code currentBalance} は返してよい。
 *
 * @param cardId               対象カード ID（UUIDv7）
 * @param providerId           プロバイダー ID
 * @param providerDisplayName  プロバイダー表示名（運営マスタなので暗号化対象外）
 * @param providerType         プロバイダー種別（SELF_ISSUED_STAMP / SELF_ISSUED_BALANCE）
 * @param last4                カード番号下 4 桁（平文カラム、店主側に「どのカードか」最小確認情報として返す）
 * @param currentStampCount    現在のスタンプ数（STAMP 型のみ非 null、BALANCE 型は null）
 * @param currentBalance       現在の残高（BALANCE 型のみ非 null、STAMP 型は null）
 */
public record ResolveTokenResponse(
        UUID cardId,
        UUID providerId,
        String providerDisplayName,
        PointCardProviderType providerType,
        String last4,
        Integer currentStampCount,
        BigDecimal currentBalance
) {

    /**
     * Entity と関連プロバイダーから DTO を構築する。
     * 暗号化対象（barcodeValue / displayName / nickname / memo）は一切含めない。
     *
     * @param card     カード Entity
     * @param provider プロバイダー Entity
     */
    public static ResolveTokenResponse from(UserPointCardEntity card,
                                            PointCardProviderEntity provider) {
        return new ResolveTokenResponse(
                card.getId(),
                provider.getId(),
                provider.getDisplayName(),
                provider.getType(),
                card.getLast4(),
                card.getStampCount(),
                card.getBalance()
        );
    }
}
