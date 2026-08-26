package com.mannschaft.app.faq.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

/**
 * F21.1 §5.5: FAQ 編集画面用ペイロード（管理 GET レスポンス）。
 *
 * <p>対象団体のカテゴリ（{@code category}）と、そのカテゴリの固定6問（未回答含む全件）、
 * 自由質問（display_order 昇順）を返す。
 * 固定質問の文言はバックエンドに保持せず、{@code questionKey}（i18n キー解決用の enum 名）のみ返す。
 * フロントエンドが i18n で質問文を描画する。</p>
 *
 * <p>カテゴリは団体の種別（チーム template / 組織 orgType）から
 * {@code com.mannschaft.app.faq.service.FaqCategoryResolver} で解決される。
 * {@code fixedQuestions} は解決カテゴリに属する 6 問のみを displayOrder 昇順で返す。</p>
 *
 * <p>設計書: docs/features/F21.1_geo_optimization.md §5.5.6</p>
 *
 * @param category       対象団体のFAQカテゴリ（{@code FaqCategory} の name。例: {@code "SPORTS"}）
 * @param fixedQuestions 解決カテゴリの固定6問（displayOrder 昇順・全件。未回答は answer=null）
 * @param customFaqs     自由質問（displayOrder 昇順）
 */
@Builder
@Schema(description = "FAQ 編集画面用ペイロード（カテゴリ + 固定6問 + 自由質問）")
public record FaqEditorResponse(

        @Schema(description = "対象団体のFAQカテゴリ（FaqCategory の name）", example = "SPORTS")
        String category,

        @Schema(description = "解決カテゴリの固定6問（displayOrder 昇順・全件）")
        List<FixedFaqItem> fixedQuestions,

        @Schema(description = "自由質問（displayOrder 昇順）")
        List<CustomFaqItem> customFaqs) {

    /**
     * 固定FAQ 1問。
     *
     * @param questionKey  固定質問キー（{@code FixedFaqQuestion} の name。i18n キー解決に用いる）
     * @param displayOrder 表示順（1始まり）
     * @param answer       回答本文（未回答の場合は null）
     */
    @Builder
    @Schema(description = "固定FAQ 1問")
    public record FixedFaqItem(

            @Schema(description = "固定質問キー（FixedFaqQuestion の name）", example = "ACTIVITY")
            String questionKey,

            @Schema(description = "表示順（1始まり）", example = "1")
            int displayOrder,

            @Schema(description = "回答本文（未回答の場合は null）", nullable = true)
            String answer) {
    }

    /**
     * 自由FAQ 1問。
     *
     * @param id           FAQ ID（UUID 文字列）
     * @param questionText 質問文
     * @param answer       回答本文
     * @param displayOrder 表示順
     */
    @Builder
    @Schema(description = "自由FAQ 1問")
    public record CustomFaqItem(

            @Schema(description = "FAQ ID（UUID 文字列）")
            String id,

            @Schema(description = "質問文")
            String questionText,

            @Schema(description = "回答本文")
            String answer,

            @Schema(description = "表示順")
            int displayOrder) {
    }
}
