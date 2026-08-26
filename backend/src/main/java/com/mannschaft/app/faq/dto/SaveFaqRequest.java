package com.mannschaft.app.faq.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * F21.1 §5.5: FAQ 一括 upsert リクエスト（管理 PUT）。
 *
 * <p>固定質問は (scope, questionKey) で UPSERT（answer 空はクリア=論理削除）、
 * 自由質問はリクエストの id 有無で差分適用（id 無し=新規・id 有り=更新・リクエストに無い既存=論理削除）する。</p>
 *
 * <p>Bean Validation で表現できる制約（最大長・件数上限）はここで宣言し、
 * 業務制約（questionKey の妥当性・重複・質問文必須）はサービス層で検証する。</p>
 *
 * <p>設計書: docs/features/F21.1_geo_optimization.md §5.5.6</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "FAQ 一括 upsert リクエスト")
public class SaveFaqRequest {

    /** 自由質問の登録件数上限。 */
    public static final int MAX_CUSTOM_FAQS = 7;

    /** 回答本文の最大長。 */
    public static final int MAX_ANSWER_LENGTH = 1000;

    /** 質問文の最大長。 */
    public static final int MAX_QUESTION_TEXT_LENGTH = 255;

    /**
     * 固定質問の回答リスト。answer が空文字 / null の項目はその固定質問の回答をクリア（論理削除）する。
     */
    @Valid
    @Schema(description = "固定質問の回答リスト（answer 空はクリア）")
    private List<FixedAnswer> fixedAnswers = new ArrayList<>();

    /**
     * 自由質問リスト。件数上限は {@link #MAX_CUSTOM_FAQS} 件。
     */
    @Valid
    @Size(max = MAX_CUSTOM_FAQS, message = "自由質問は最大7件までです")
    @Schema(description = "自由質問リスト（最大7件）")
    private List<CustomFaqInput> customFaqs = new ArrayList<>();

    /**
     * 固定質問の回答 1件。
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "固定質問の回答 1件")
    public static class FixedAnswer {

        /** 固定質問キー（{@code FixedFaqQuestion} の name）。妥当性はサービス層で検証。 */
        @Schema(description = "固定質問キー（FixedFaqQuestion の name）", example = "ACTIVITY")
        private String questionKey;

        /** 回答本文。空文字 / null はクリア（論理削除）扱い。 */
        @Size(max = MAX_ANSWER_LENGTH, message = "回答は最大1000文字までです")
        @Schema(description = "回答本文（空はクリア）", nullable = true)
        private String answer;
    }

    /**
     * 自由質問の入力 1件。
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "自由質問の入力 1件")
    public static class CustomFaqInput {

        /** 既存 FAQ の ID。新規追加の場合は null。 */
        @Schema(description = "既存 FAQ の ID（新規は null）", nullable = true)
        private UUID id;

        /** 質問文。必須（空白不可）。妥当性はサービス層でも検証。 */
        @Size(max = MAX_QUESTION_TEXT_LENGTH, message = "質問文は最大255文字までです")
        @Schema(description = "質問文（必須・最大255文字）")
        private String questionText;

        /** 回答本文。 */
        @Size(max = MAX_ANSWER_LENGTH, message = "回答は最大1000文字までです")
        @Schema(description = "回答本文（最大1000文字）")
        private String answer;

        /** 表示順。 */
        @Schema(description = "表示順")
        private Integer displayOrder;
    }
}
