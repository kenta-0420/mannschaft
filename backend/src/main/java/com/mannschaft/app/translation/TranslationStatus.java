package com.mannschaft.app.translation;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 翻訳コンテンツのステータス。
 */
public enum TranslationStatus {

    /** 下書き */
    DRAFT,

    /** レビュー中 */
    IN_REVIEW,

    /** 公開済み */
    PUBLISHED,

    /** 原文更新により再翻訳が必要 */
    NEEDS_UPDATE;

    /**
     * 許可されるステータス遷移マップ。
     * <ul>
     *   <li>DRAFT → IN_REVIEW, PUBLISHED</li>
     *   <li>IN_REVIEW → PUBLISHED, DRAFT（差し戻し）</li>
     *   <li>PUBLISHED → DRAFT（非公開に戻す）</li>
     *   <li>NEEDS_UPDATE → DRAFT（再翻訳開始）, PUBLISHED（更新不要と判断）</li>
     * </ul>
     */
    private static final Map<TranslationStatus, Set<TranslationStatus>> ALLOWED_TRANSITIONS = Map.of(
            DRAFT, EnumSet.of(IN_REVIEW, PUBLISHED),
            IN_REVIEW, EnumSet.of(PUBLISHED, DRAFT),
            PUBLISHED, EnumSet.of(DRAFT),
            NEEDS_UPDATE, EnumSet.of(DRAFT, PUBLISHED)
    );

    /**
     * 指定ステータスへの遷移が許可されるかを判定する。
     *
     * @param target 遷移先ステータス
     * @return 遷移可能な場合 true
     */
    public boolean canTransitionTo(TranslationStatus target) {
        Set<TranslationStatus> allowed = ALLOWED_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(target);
    }
}
