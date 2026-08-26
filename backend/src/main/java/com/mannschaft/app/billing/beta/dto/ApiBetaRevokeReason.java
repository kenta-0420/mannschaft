package com.mannschaft.app.billing.beta.dto;

import com.mannschaft.app.billing.beta.BetaRevokeReason;

/**
 * F20.3 ベータ特典: シスアド取消 API が受け付ける取消事由（設計書 02 §4.2）。
 *
 * <p>ドメインの {@link BetaRevokeReason} は {@code WITHDRAWAL}（退会確定のシステム専用値）を含むが、
 * API からは指定させない（{@code AccountPurgedEvent} 起点のシステム取消のみが使う値・03 §4）。よって
 * API 境界には <b>{@code WITHDRAWAL} を含まない専用 enum</b> を置き、{@code WITHDRAWAL} を JSON で送ると
 * Jackson のバインド失敗で 400 に倒れる（設計書 02 §4.2・DTO 一次ゲート）。</p>
 */
public enum ApiBetaRevokeReason {

    /** 規約違反による取消。 */
    TERMS_VIOLATION,

    /** アカウント譲渡が確認された取消。 */
    ACCOUNT_TRANSFER,

    /** その他（監査メモ併記を推奨）。 */
    OTHER;

    /** API 事由をドメイン事由へ変換する（{@code WITHDRAWAL} は本 enum に存在しないため決して生成されない）。 */
    public BetaRevokeReason toDomain() {
        return switch (this) {
            case TERMS_VIOLATION -> BetaRevokeReason.TERMS_VIOLATION;
            case ACCOUNT_TRANSFER -> BetaRevokeReason.ACCOUNT_TRANSFER;
            case OTHER -> BetaRevokeReason.OTHER;
        };
    }
}
