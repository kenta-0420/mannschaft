package com.mannschaft.app.faq;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * F21.1 §5.5: 固定FAQ質問（編集不可・カテゴリ別）。
 *
 * <p>GEO（生成AI検索）で最も引用されやすい FAQPage 構造化データを充実させるため、
 * 団体の{@link FaqCategory カテゴリ}ごとに 6 つの定番質問を固定で用意する
 * （7 カテゴリ × 6 問 = 42 問）。団体のカテゴリは
 * {@link com.mannschaft.app.faq.service.FaqCategoryResolver} がチームの {@code template} /
 * 組織の {@code orgType} から解決する。</p>
 *
 * <p>質問文そのものはバックエンドに保持せず、{@link #i18nKey() i18nキー}（{@code "faq.fixed."+name().toLowerCase()}）
 * のみを持つ。実際の文言はフロントエンドの i18n（{@code faq.json}）で6言語描画する
 * （DDL の {@code public_faqs.question_text} は固定質問では NULL）。</p>
 *
 * <p>{@link #displayOrder()} はカテゴリ内 1〜6 の表示順。public_faqs.display_order の初期値・
 * 既定表示順として用いる。{@link #name()} を {@code public_faqs.question_key} に保存する。</p>
 *
 * <p>設計書: docs/features/F21.1_geo_optimization.md §5.5</p>
 */
public enum FixedFaqQuestion {

    // === SPORTS（スポーツ・運動系）===
    /** どんな競技・活動をしていますか？ */
    SPORTS_ACTIVITY(FaqCategory.SPORTS, 1),
    /** 活動場所・グラウンドはどこですか？ */
    SPORTS_LOCATION(FaqCategory.SPORTS, 2),
    /** 活動日・練習日はいつですか？ */
    SPORTS_SCHEDULE(FaqCategory.SPORTS, 3),
    /** 入会・体験参加するには？ */
    SPORTS_JOIN(FaqCategory.SPORTS, 4),
    /** 会費・費用はどのくらい？ */
    SPORTS_COST(FaqCategory.SPORTS, 5),
    /** 対象レベル・年代は？（初心者歓迎か） */
    SPORTS_LEVEL(FaqCategory.SPORTS, 6),

    // === HEALTH（医療・健康系）===
    /** どんな施術・診療を受けられますか？ */
    HEALTH_SERVICE(FaqCategory.HEALTH, 1),
    /** 診療時間・休診日は？ */
    HEALTH_HOURS(FaqCategory.HEALTH, 2),
    /** 予約は必要ですか？方法は？ */
    HEALTH_RESERVE(FaqCategory.HEALTH, 3),
    /** 保険は使えますか？ */
    HEALTH_INSURANCE(FaqCategory.HEALTH, 4),
    /** 料金の目安は？ */
    HEALTH_COST(FaqCategory.HEALTH, 5),
    /** アクセス・駐車場は？ */
    HEALTH_ACCESS(FaqCategory.HEALTH, 6),

    // === EDUCATION（教育系）===
    /** どんな内容を学べますか？ */
    EDUCATION_SUBJECT(FaqCategory.EDUCATION, 1),
    /** 対象年齢・学年は？ */
    EDUCATION_TARGET(FaqCategory.EDUCATION, 2),
    /** 開講日・時間割は？ */
    EDUCATION_SCHEDULE(FaqCategory.EDUCATION, 3),
    /** 体験・見学はできますか？ */
    EDUCATION_TRIAL(FaqCategory.EDUCATION, 4),
    /** 月謝・費用は？ */
    EDUCATION_COST(FaqCategory.EDUCATION, 5),
    /** 教室の場所・アクセスは？ */
    EDUCATION_ACCESS(FaqCategory.EDUCATION, 6),

    // === BUSINESS（ビジネス系）===
    /** どんな商品・サービスを提供していますか？ */
    BUSINESS_SERVICE(FaqCategory.BUSINESS, 1),
    /** 営業時間・定休日は？ */
    BUSINESS_HOURS(FaqCategory.BUSINESS, 2),
    /** 予約・来店方法は？ */
    BUSINESS_RESERVE(FaqCategory.BUSINESS, 3),
    /** 料金・価格帯は？ */
    BUSINESS_PRICE(FaqCategory.BUSINESS, 4),
    /** 場所・アクセス・駐車場は？ */
    BUSINESS_ACCESS(FaqCategory.BUSINESS, 5),
    /** 問い合わせ方法は？ */
    BUSINESS_CONTACT(FaqCategory.BUSINESS, 6),

    // === COMMUNITY（コミュニティ・地域・非営利系）===
    /** どんな目的・活動の団体ですか？ */
    COMMUNITY_MISSION(FaqCategory.COMMUNITY, 1),
    /** 活動地域・範囲は？ */
    COMMUNITY_AREA(FaqCategory.COMMUNITY, 2),
    /** 主な行事・イベントは？ */
    COMMUNITY_EVENTS(FaqCategory.COMMUNITY, 3),
    /** 参加・入会・ボランティアするには？ */
    COMMUNITY_JOIN(FaqCategory.COMMUNITY, 4),
    /** 会費・参加費は？ */
    COMMUNITY_COST(FaqCategory.COMMUNITY, 5),
    /** どんな人が対象ですか？ */
    COMMUNITY_TARGET(FaqCategory.COMMUNITY, 6),

    // === RESIDENCE（居住・区分所有系）===
    /** どんな組合・コミュニティですか？ */
    RESIDENCE_ABOUT(FaqCategory.RESIDENCE, 1),
    /** 対象の建物・地域は？ */
    RESIDENCE_AREA(FaqCategory.RESIDENCE, 2),
    /** 主な規約・ルールは？ */
    RESIDENCE_RULES(FaqCategory.RESIDENCE, 3),
    /** 管理費・会費は？ */
    RESIDENCE_FEE(FaqCategory.RESIDENCE, 4),
    /** 行事・活動はありますか？ */
    RESIDENCE_EVENTS(FaqCategory.RESIDENCE, 5),
    /** 問い合わせ・連絡方法は？ */
    RESIDENCE_CONTACT(FaqCategory.RESIDENCE, 6),

    // === GENERAL（汎用・その他）===
    /** どんな活動をしている団体ですか？ */
    GENERAL_ACTIVITY(FaqCategory.GENERAL, 1),
    /** 主な活動場所（地域）はどこですか？ */
    GENERAL_LOCATION(FaqCategory.GENERAL, 2),
    /** 参加・入会するにはどうすればよいですか？ */
    GENERAL_JOIN(FaqCategory.GENERAL, 3),
    /** 費用（会費など）はかかりますか？ */
    GENERAL_COST(FaqCategory.GENERAL, 4),
    /** どんな人が対象ですか？ */
    GENERAL_TARGET(FaqCategory.GENERAL, 5),
    /** 初心者でも参加できますか？ */
    GENERAL_BEGINNER(FaqCategory.GENERAL, 6);

    private final FaqCategory category;
    private final int displayOrder;
    private final String i18nKey;

    FixedFaqQuestion(FaqCategory category, int displayOrder) {
        this.category = category;
        this.displayOrder = displayOrder;
        this.i18nKey = "faq.fixed." + name().toLowerCase();
    }

    /**
     * 所属カテゴリ。
     *
     * @return この固定質問が属する {@link FaqCategory}
     */
    public FaqCategory category() {
        return category;
    }

    /**
     * カテゴリ内の既定表示順（public_faqs.display_order の初期値）。
     *
     * @return カテゴリ内 1〜6 の表示順
     */
    public int displayOrder() {
        return displayOrder;
    }

    /**
     * 質問文の i18n キー（フロントエンド描画用）。
     *
     * @return i18n キー（例: {@code "faq.fixed.sports_activity"}）
     */
    public String i18nKey() {
        return i18nKey;
    }

    /**
     * 質問文の i18n キー（{@link #i18nKey()} のエイリアス。後方互換用）。
     *
     * @return i18n キー
     */
    public String questionKey() {
        return i18nKey;
    }

    /**
     * enum 名（= public_faqs.question_key に保存する値）から {@link FixedFaqQuestion} を解決する。
     *
     * <p>{@code public_faqs.question_key} には enum の {@link #name()}（例: {@code "SPORTS_ACTIVITY"}）を保存する。
     * 自由質問の場合は question_key が NULL のため、その値はここに渡されない。</p>
     *
     * @param key question_key の値（enum 名）。NULL の場合は空を返す
     * @return 一致する固定質問。一致しなければ空（= 不正な key）
     */
    public static Optional<FixedFaqQuestion> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (FixedFaqQuestion q : values()) {
            if (q.name().equals(key)) {
                return Optional.of(q);
            }
        }
        return Optional.empty();
    }

    /**
     * 指定カテゴリに属する固定質問を displayOrder 昇順で返す。
     *
     * @param category 対象カテゴリ
     * @return 当該カテゴリの 6 問（displayOrder 昇順）
     */
    public static List<FixedFaqQuestion> ofCategory(FaqCategory category) {
        List<FixedFaqQuestion> result = new ArrayList<>();
        for (FixedFaqQuestion q : values()) {
            if (q.category == category) {
                result.add(q);
            }
        }
        result.sort((a, b) -> Integer.compare(a.displayOrder, b.displayOrder));
        return result;
    }
}
