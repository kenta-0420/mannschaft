package com.mannschaft.app.common.visibility;

import java.util.Objects;

/**
 * {@code ContentVisibilityChecker.decide(...)} の戻り値。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §7.2 完全一致。
 *
 * <p>監査ログ・デバッグ用途で詳細な判定結果を保持する。
 *
 * @param referenceType  対象コンテンツの種別
 * @param contentId      対象コンテンツの ID
 * @param allowed        true なら閲覧許可、false なら拒否
 * @param denyReason     allowed=true のとき必ず {@code null}、allowed=false のとき必須
 * @param resolvedLevel  解決された StandardVisibility ({@code null} 可)。CUSTOM 等で
 *                       特定できない場合は {@code null}
 * @param detail         任意の説明 (監査ログ用、{@code null} 可)
 */
public record VisibilityDecision(
        ReferenceType referenceType,
        Long contentId,
        boolean allowed,
        DenyReason denyReason,
        StandardVisibility resolvedLevel,
        String detail
) {

    /**
     * インバリアント検証。
     * <ul>
     *   <li>allowed=true のとき denyReason は null でなければならない
     *   <li>allowed=false のとき denyReason は必須 (null 不可)
     * </ul>
     */
    public VisibilityDecision {
        Objects.requireNonNull(referenceType, "referenceType must not be null");
        if (allowed && denyReason != null) {
            throw new IllegalArgumentException(
                "denyReason must be null when allowed=true");
        }
        if (!allowed && denyReason == null) {
            throw new IllegalArgumentException(
                "denyReason must not be null when allowed=false");
        }
    }

    /**
     * 許可の判定結果を生成する。
     *
     * @param type 対象コンテンツの種別
     * @param id   対象コンテンツの ID
     * @return allowed=true、denyReason=null の {@link VisibilityDecision}
     */
    public static VisibilityDecision allow(ReferenceType type, Long id) {
        return new VisibilityDecision(type, id, true, null, null, null);
    }

    /**
     * 拒否の判定結果を生成する (詳細メッセージなし)。
     *
     * @param type   対象コンテンツの種別
     * @param id     対象コンテンツの ID
     * @param reason 拒否理由
     * @return allowed=false の {@link VisibilityDecision}
     */
    public static VisibilityDecision deny(ReferenceType type, Long id, DenyReason reason) {
        return deny(type, id, reason, null);
    }

    /**
     * 拒否の判定結果を生成する (詳細メッセージあり)。
     *
     * @param type   対象コンテンツの種別
     * @param id     対象コンテンツの ID
     * @param reason 拒否理由
     * @param detail 監査ログ用の追加説明
     * @return allowed=false の {@link VisibilityDecision}
     */
    public static VisibilityDecision deny(
            ReferenceType type, Long id, DenyReason reason, String detail) {
        Objects.requireNonNull(reason, "denyReason must not be null when denying");
        return new VisibilityDecision(type, id, false, reason, null, detail);
    }
}
