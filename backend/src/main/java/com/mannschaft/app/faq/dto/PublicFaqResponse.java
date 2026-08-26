package com.mannschaft.app.faq.dto;

/**
 * F21.1 §5.5.6: 公開FAQレスポンス DTO（{@code GET /api/v1/public/{teams|organizations}/{id}/faqs}）。
 *
 * <p>回答済み（{@code answer_text} 非空・{@code deleted_at} が NULL）の FAQ のみを返す。
 * 並び順は「固定質問（{@code question_key} 非NULL）を
 * {@link com.mannschaft.app.faq.FixedFaqQuestion#displayOrder()} 昇順
 * → 自由質問（{@code question_key} NULL）を {@code display_order} 昇順」。</p>
 *
 * <p>固定質問は {@code questionKey}（enum 名）をそのまま返し、FE 側が
 * {@code faq.fixed.{key}} の i18n で質問文を描画する（{@code questionText} は {@code null}）。
 * 自由質問は {@code questionKey} を {@code null} とし、{@code questionText} に保存値を返す。</p>
 *
 * <p><strong>抑制DTO原則（PII / 内部識別子の禁則）</strong>: id・scopeId・createdBy・
 * createdAt / updatedAt 等の内部識別子・状態は<strong>一切含めない</strong>。
 * 公開ページ／FAQPage JSON-LD の構築に必要な「質問キー・質問文・回答」のみを返す。</p>
 *
 * @param questionKey 固定質問の i18n キー解決用 enum 名（例: {@code "ACTIVITY"}）。自由質問では {@code null}
 * @param questionText 自由質問の質問文。固定質問では {@code null}（FE が i18n で描画）
 * @param answer 回答本文（非空）
 */
public record PublicFaqResponse(
        String questionKey,
        String questionText,
        String answer
) {
}
