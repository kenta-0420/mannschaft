package com.mannschaft.app.faq;

import java.util.Optional;

/**
 * F21.1 §5.5: 固定FAQ質問（編集不可・全団体共通）。
 *
 * <p>GEO（生成AI検索）で最も引用されやすい FAQPage 構造化データを充実させるため、
 * 全団体に共通する6つの定番質問を固定で用意する。</p>
 *
 * <p>質問文そのものはバックエンドに保持せず、{@link #questionKey() i18nキー} のみを持つ。
 * 実際の文言はフロントエンドの i18n（{@code faq.json}）で6言語描画する
 * （DDL の {@code public_faqs.question_text} は固定質問では NULL）。</p>
 *
 * <p>{@link #displayOrder()} は public_faqs.display_order の初期値・既定表示順として用いる。</p>
 */
public enum FixedFaqQuestion {

    /** どんな活動をしているか */
    ACTIVITY(1, "faq.fixed.activity"),
    /** 活動場所はどこか */
    LOCATION(2, "faq.fixed.location"),
    /** どうやって参加・入会するか */
    JOIN(3, "faq.fixed.join"),
    /** 費用はいくらか */
    COST(4, "faq.fixed.cost"),
    /** 対象（年齢・レベル等）は誰か */
    TARGET(5, "faq.fixed.target"),
    /** 初心者でも大丈夫か */
    BEGINNER(6, "faq.fixed.beginner");

    private final int displayOrder;
    private final String questionKey;

    FixedFaqQuestion(int displayOrder, String questionKey) {
        this.displayOrder = displayOrder;
        this.questionKey = questionKey;
    }

    /**
     * 既定表示順（public_faqs.display_order の初期値）。
     *
     * @return 1始まりの表示順
     */
    public int displayOrder() {
        return displayOrder;
    }

    /**
     * 質問文の i18n キー（フロントエンド描画用）。
     *
     * @return i18n キー（例: {@code "faq.fixed.activity"}）
     */
    public String questionKey() {
        return questionKey;
    }

    /**
     * enum 名（= public_faqs.question_key に保存する値）から {@link FixedFaqQuestion} を解決する。
     *
     * <p>{@code public_faqs.question_key} には enum の {@link #name()}（例: {@code "ACTIVITY"}）を保存する。
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
}
