package com.mannschaft.app.faq;

/**
 * F21.1 §5.5: FAQ カテゴリ。
 *
 * <p>団体の業種・種別に応じた「カテゴリ別固定質問」を出し分けるための分類。
 * チームの {@code template} または組織の {@code orgType} から
 * {@link com.mannschaft.app.faq.service.FaqCategoryResolver} で解決する。</p>
 *
 * <p>各カテゴリには {@link FixedFaqQuestion} が 6 問ずつ紐づく（7 カテゴリ × 6 問 = 42 問）。</p>
 *
 * <p>設計書: docs/features/F21.1_geo_optimization.md §5.5</p>
 */
public enum FaqCategory {

    /** スポーツ・運動系（クラブ・サークル・ジム等）。 */
    SPORTS,
    /** 医療・健康系（クリニック・整骨院・病院等）。 */
    HEALTH,
    /** 教育系（教室・スクール・学校等）。 */
    EDUCATION,
    /** ビジネス系（企業・飲食店・店舗・美容サロン等）。 */
    BUSINESS,
    /** コミュニティ・地域・非営利系（自治会・ボランティア・NPO・協会・行政等）。 */
    COMMUNITY,
    /** 居住・区分所有系（マンション管理組合・家族等）。 */
    RESIDENCE,
    /** 汎用（その他・未分類・不明）。 */
    GENERAL
}
